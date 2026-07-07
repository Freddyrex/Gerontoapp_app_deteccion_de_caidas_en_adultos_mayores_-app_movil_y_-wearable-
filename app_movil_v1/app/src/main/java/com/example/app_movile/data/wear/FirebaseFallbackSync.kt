package com.example.app_movile.data.wear

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.example.app_movile.data.db.entities.Medicamento
import com.example.app_movile.data.remote.FirebaseMedManager
import com.example.app_movile.util.PersonaManager
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * FirebaseFallbackSync — Canal alternativo de sincronización Teléfono ↔ Reloj
 *
 * Nodos Firebase usados (esquema NoSQL plano):
 *
 *   medications/{personaId}/{medId}          → escritura desde teléfono, lectura desde reloj
 *   fallEvents/{deviceId}/{eventId}          → escritura desde reloj (caída detectada)
 *   helpRequests/{deviceId}/{requestId}      → escritura desde reloj (llamado de ayuda)
 *   sync/{deviceId}                          → metadatos de sincronización
 *   notifications/{uid}/{notifId}            → notificaciones al cuidador
 */
object FirebaseFallbackSync {

    private const val TAG = "FirebaseFallbackSync"
    private const val FIREBASE_URL = "https://gerento-74200-default-rtdb.firebaseio.com"
    private const val WEARABLE_TIMEOUT_MS = 4_000L

    private val db   = FirebaseDatabase.getInstance(FIREBASE_URL)
    private val auth = FirebaseAuth.getInstance()

    // ─── MEDICAMENTOS ─────────────────────────────────────────────

    /**
     * Sincroniza medicamentos. Escribe siempre en Firebase bajo
     * medications/{personaId} y también intenta el canal Wearable.
     */
    suspend fun sincronizarMedicamentos(
        context: Context,
        medicamentos: List<Medicamento>
    ): SyncResult = withContext(Dispatchers.IO) {

        val uid = auth.currentUser?.uid
        if (uid == null) {
            Log.w(TAG, "sincronizarMedicamentos: usuario no autenticado")
            return@withContext SyncResult(canal = Canal.NINGUNO, exitoso = false,
                error = "Usuario no autenticado")
        }

        // 1) Escribir en medications/{personaId}
        val firebaseOk = escribirMedicamentosEnFirebase(context, medicamentos)

        // 1b) Notificar al bridge del reloj si está listo
        try {
            val bridge = com.example.app_movile.sync.WatchBridgeManager(context)
            if (bridge.estaListo()) bridge.sincronizarMedicamentosAlReloj(medicamentos)
        } catch (e: Exception) {
            Log.w(TAG, "Bridge sync al reloj falló (no crítico): ${e.message}")
        }

        // 2) Intentar también por Wearable Data Layer
        val wearableDisponible = isWearableDisponible(context)
        if (wearableDisponible) {
            val wearOk = withTimeoutOrNull(WEARABLE_TIMEOUT_MS) {
                intentarEnvioWearable(context, medicamentos)
            } ?: false
            if (wearOk) {
                marcarSyncMeta(uid, Canal.WEARABLE)
                Log.d(TAG, "Sync OK → Wearable (+ Firebase respaldo)")
                return@withContext SyncResult(canal = Canal.WEARABLE, exitoso = true)
            }
        }

        marcarSyncMeta(uid, Canal.FIREBASE)
        if (firebaseOk) Log.d(TAG, "Sync OK → Firebase RTDB")
        else Log.e(TAG, "Sync FALLÓ en ambos canales")

        SyncResult(
            canal   = Canal.FIREBASE,
            exitoso = firebaseOk,
            error   = if (!firebaseOk) "Error escribiendo en Firebase" else null
        )
    }

    // ─── CAÍDAS  (fallEvents/{deviceId}/{eventId}) ────────────────

    /**
     * Severidades válidas:
     *   "movimiento_brusco" | "caida_leve" | "caida_grave"
     */
    suspend fun publicarAlertaCaida(
        uid: String,
        deviceId: String,
        personaId: String,
        timestamp: Long,
        latitud: Double?,
        longitud: Double?,
        severidad: String,
        datosAcelerometro: Map<String, Any>? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val datos: MutableMap<String, Any?> = mutableMapOf(
                "personaId"   to personaId,
                "timestamp_ms" to timestamp,
                "severity"    to severidad,
                "status"      to "pendiente",    // pendiente | alerta_emitida | ignorado
                "processed"   to false
            )
            if (latitud != null && longitud != null) {
                datos["location"] = mapOf("lat" to latitud, "lng" to longitud)
            }
            if (datosAcelerometro != null) datos["accelData"] = datosAcelerometro

            // Nodo histórico por dispositivo
            db.getReference("fallEvents/$deviceId").push().setValue(datos).await()

            // Nodo rápido para notificación en tiempo real
            db.getReference("ultimaCaida/$uid").updateChildren(
                datos + mapOf("deviceId" to deviceId)
            ).await()

            // Crear notificación al cuidador si la severidad lo amerita
            if (severidad != "movimiento_brusco") {
                crearNotificacionCuidador(uid, personaId, "fall", severidad, timestamp)
            }

            // Actualizar sync metadata del dispositivo
            db.getReference("sync/$deviceId/lastPush_ms").setValue(timestamp).await()

            Log.d(TAG, "Alerta caída publicada: severity=$severidad device=$deviceId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error publicando caída: ${e.message}")
            false
        }
    }

    // ─── LLAMADO DE AYUDA  (helpRequests/{deviceId}/{requestId}) ──

    suspend fun publicarSolicitudAyuda(
        uid: String,
        deviceId: String,
        personaId: String,
        timestamp: Long,
        latitud: Double?,
        longitud: Double?,
        mensaje: String = "Necesito asistencia"
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val datos: MutableMap<String, Any?> = mutableMapOf(
                "personaId"    to personaId,
                "timestamp_ms" to timestamp,
                "message"      to mensaje,
                "status"       to "pendiente"
            )
            if (latitud != null && longitud != null) {
                datos["location"] = mapOf("lat" to latitud, "lng" to longitud)
            }

            // helpRequests/{deviceId}/{pushId}
            db.getReference("helpRequests/$deviceId").push().setValue(datos).await()

            // Notificación al cuidador
            crearNotificacionCuidador(uid, personaId, "manual_help", null, timestamp)

            // Sync meta
            db.getReference("sync/$deviceId/lastPush_ms").setValue(timestamp).await()

            Log.d(TAG, "Solicitud de ayuda publicada: device=$deviceId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error publicando solicitud de ayuda: ${e.message}")
            false
        }
    }

    // ─── NOTIFICACIONES AL CUIDADOR ───────────────────────────────

    private suspend fun crearNotificacionCuidador(
        uid: String,
        personaId: String,
        type: String,
        severity: String?,
        timestamp: Long
    ) {
        try {
            val notif: MutableMap<String, Any?> = mutableMapOf(
                "type"         to type,
                "personaId"    to personaId,
                "timestamp_ms" to timestamp,
                "read"         to false
            )
            if (severity != null) notif["severity"] = severity
            db.getReference("notifications/$uid").push().setValue(notif).await()
        } catch (_: Exception) {}
    }

    // ─── OBSERVADORES ─────────────────────────────────────────────

    fun observarEstadoConexion(
        onCambio: (canal: Canal, ultimaSync: Long) -> Unit,
        onError: (String) -> Unit
    ): EstadoListenerRegistration {
        val uid = auth.currentUser?.uid ?: return EstadoListenerRegistration.empty()
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val canalStr   = snapshot.child("canalActivo").value as? String ?: "desconocido"
                val ultimaSync = snapshot.child("ultimaSync").value as? Long ?: 0L
                val canal = when (canalStr) {
                    "wearable" -> Canal.WEARABLE
                    "firebase" -> Canal.FIREBASE
                    else       -> Canal.NINGUNO
                }
                onCambio(canal, ultimaSync)
            }
            override fun onCancelled(error: DatabaseError) { onError(error.message) }
        }
        val ref = db.getReference("sincronizacion/$uid")
        ref.addValueEventListener(listener)
        return EstadoListenerRegistration(ref, listener)
    }

    fun observarPendientes(onPendiente: (Boolean) -> Unit): EstadoListenerRegistration {
        val uid = auth.currentUser?.uid ?: return EstadoListenerRegistration.empty()
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                onPendiente(snapshot.child("pendiente").value as? Boolean ?: false)
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        val ref = db.getReference("sincronizacion/$uid")
        ref.addValueEventListener(listener)
        return EstadoListenerRegistration(ref, listener)
    }

    // ─── HELPERS INTERNOS ─────────────────────────────────────────

    private suspend fun isWearableDisponible(context: Context): Boolean =
        withTimeoutOrNull(2_000L) {
            try {
                val caps = Wearable.getCapabilityClient(context)
                    .getCapability("siag_wear_app", CapabilityClient.FILTER_REACHABLE).await()
                caps.nodes.isNotEmpty()
            } catch (e: Exception) { false }
        } ?: false

    private suspend fun intentarEnvioWearable(context: Context, meds: List<Medicamento>): Boolean =
        try {
            WearSyncRepository(context).sincronizarMedicamentos(meds)
            true
        } catch (e: Exception) { false }

    private suspend fun escribirMedicamentosEnFirebase(
        context: Context,
        medicamentos: List<Medicamento>
    ): Boolean {
        return try {
            val personaId = PersonaManager.getPersonaId(context)
            val ahora = System.currentTimeMillis()
            // Escribir cada medicamento bajo medications/{personaId}/{id}
            for (med in medicamentos) {
                db.getReference("medications/$personaId/${med.id}")
                    .setValue(FirebaseMedManager.medToMap(med)).await()
            }
            // Metadato de sync para el reloj
            val uid = auth.currentUser?.uid
            if (uid != null) {
                db.getReference("sincronizacion/$uid").updateChildren(
                    mapOf("ultimaSync" to ahora, "pendiente" to true)
                ).await()
            }
            Log.d(TAG, "Medicamentos escritos: ${medicamentos.size}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error en Firebase: ${e.message}")
            false
        }
    }

    private suspend fun marcarSyncMeta(uid: String, canal: Canal) {
        try {
            db.getReference("sincronizacion/$uid").updateChildren(
                mapOf(
                    "canalActivo" to canal.name.lowercase(),
                    "ultimaSync"  to System.currentTimeMillis()
                )
            ).await()
        } catch (_: Exception) {}
    }

    fun hayInternet(context: Context): Boolean {
        val cm   = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net  = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

// ─── Modelos ──────────────────────────────────────────────────

enum class Canal { WEARABLE, FIREBASE, NINGUNO }

data class SyncResult(
    val canal: Canal,
    val exitoso: Boolean,
    val error: String? = null
)

class EstadoListenerRegistration(
    private val ref: com.google.firebase.database.DatabaseReference,
    private val listener: ValueEventListener
) {
    fun remover() { ref.removeEventListener(listener) }

    companion object {
        fun empty() = EstadoListenerRegistration(
            FirebaseDatabase.getInstance().reference,
            object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) {}
                override fun onCancelled(e: DatabaseError) {}
            }
        )
    }
}
