package com.example.app_movile.data.remote

import android.content.Context
import android.util.Log
import com.example.app_movile.data.db.entities.Medicamento
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.android.gms.tasks.Tasks

/**
 * FirebaseMedManager — sincronizacion de medicamentos con Firebase RTDB
 *
 * ESTRUCTURA REAL de la base de datos (verificada contra el reloj):
 *   medications/
 *     {personaKey}/          ← "persona_" + ultimos 8 chars del UID
 *       [0]: null            ← Room empieza IDs en 1, Firebase crea indice 0
 *       [1]: { name, dose, intervalHours, referenceTime, active, ... }
 *       [2]: { ... }
 *
 * Esto es lo que el reloj lee en FirebaseSyncManager.kt linea 181:
 *   GET /medications.json
 *
 * La app movil ESCRIBE en la misma ruta para que el reloj los vea.
 */
object FirebaseMedManager {

    private const val FIREBASE_URL = "https://gerento-74200-default-rtdb.firebaseio.com/"
    private const val TAG = "FirebaseMedMgr"

    private fun ensureFirebase(context: Context) {
        try { FirebaseApp.initializeApp(context) } catch (_: Exception) {}
    }

    private fun currentUid(): String? = FirebaseAuth.getInstance().currentUser?.uid

    /**
     * Genera la personaKey igual que PersonaManager.getPersonaId():
     *   "persona_" + uid.takeLast(8)
     * Ejemplo: uid=S0lZnSeD7OZeCR1iIkoMfq6LaFh1 → "persona_q6LaFh1" (8 chars)
     * NOTA: takeLast(8) sobre ese uid = "q6LaFh1" → "persona_q6LaFh1"
     */
    private fun personaKey(): String? {
        val uid = currentUid() ?: return null
        return "persona_${uid.takeLast(8)}"
    }

    /** Referencia a medications/{personaKey}/{medId} */
    // Use server keys (push keys / string ids) instead of numeric Room ids
    private fun medRef(personaKey: String, key: String) =
        FirebaseDatabase.getInstance(FIREBASE_URL)
            .getReference("medications/$personaKey/$key")

    /** Referencia a medications/{personaKey} */
    private fun medsRootRef(personaKey: String) =
        FirebaseDatabase.getInstance(FIREBASE_URL)
            .getReference("medications/$personaKey")

    // ─── CRUD ────────────────────────────────────────────────────

    fun pushMedicamento(context: Context, med: Medicamento): String? {
        ensureFirebase(context)
        val pk = personaKey() ?: run {
            Log.w(TAG, "pushMedicamento: sin UID, abortando")
            return null
        }
        try {
            // If med.serverId exists use it, otherwise push a new key
            val ref = if (!med.serverId.isNullOrBlank()) medRef(pk, med.serverId) else medsRootRef(pk).push()
            val key = ref.key ?: return null
            Tasks.await(ref.setValue(medToMap(med)))
            Log.d(TAG, "✅ Medicamento ${med.nombre} guardado en medications/$pk/$key")
            return key
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error guardando medicamento: ${e.message}")
            return null
        }
    }

    fun updateMedicamento(context: Context, med: Medicamento) {
        ensureFirebase(context)
        val pk = personaKey() ?: return
        try {
            val key = med.serverId ?: return
            @Suppress("UNCHECKED_CAST")
            Tasks.await(medRef(pk, key).updateChildren(medToMap(med) as Map<String, Any?>))
            Log.d(TAG, "✅ Medicamento ${med.nombre} actualizado (key=$key)")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error actualizando medicamento: ${e.message}")
        }
    }

    fun deleteMedicamento(context: Context, serverKey: String?) {
        ensureFirebase(context)
        val pk = personaKey() ?: return
        if (serverKey.isNullOrBlank()) return
        try {
            Tasks.await(medRef(pk, serverKey).removeValue())
            Log.d(TAG, "✅ Medicamento $serverKey eliminado de Firebase")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error eliminando medicamento: ${e.message}")
        }
    }

    /**
     * Descarga todos los medicamentos activos desde Firebase.
     *
     * Soporta ambos formatos que puede retornar Firebase:
     *  - JSONArray : [null, {med1}, {med2}]  ← cuando Room asigna IDs 1,2,3...
     *  - JSONObject: { "1": {med1}, "2": {med2} }  ← formato alternativo
     *
     * ⚠️ IMPORTANTE: Sin esta sincronización al iniciar sesión, los medicamentos
     * no se cargarán correctamente si se desinstala y reinstala la app.
     */
    fun fetchAllForCurrentUser(context: Context): List<Medicamento> {
        ensureFirebase(context)
        val pk = personaKey() ?: run {
            Log.w(TAG, "fetchAll: sin UID - no se puede descargar medicamentos")
            return emptyList()
        }
        Log.d(TAG, "📥 [SYNC] Descargando medicamentos de medications/$pk")
        return try {
            val snap = Tasks.await(medsRootRef(pk).get())
            val list = ArrayList<Medicamento>()

            if (!snap.exists()) {
                Log.w(TAG, "⚠ Nodo medications/$pk no existe en Firebase - creando nuevo")
                return emptyList()
            }

            Log.d(TAG, "📦 [SYNC] Nodo existe, parseando ${snap.childrenCount} hijos...")

            for (child in snap.children) {
                try {
                    val key = child.key ?: continue
                    Log.d(TAG, "  📋 [SYNC] Procesando clave: $key")
                    
                    // ignore sparse array index 0 which Firebase may create as null
                    if (key == "0" && !child.hasChildren()) {
                        Log.d(TAG, "  ⏭ [SYNC] Saltando índice 0 vacío")
                        continue
                    }
                    
                    val nombre = child.child("name").getValue(String::class.java) 
                        ?: child.child("nombre").getValue(String::class.java) 
                        ?: continue
                    
                    val dosis       = child.child("dose").getValue(String::class.java)
                        ?: child.child("dosis").getValue(String::class.java)
                        ?: ""
                    val intervalHours = parseInt(child.child("intervalHours").value, 8)
                    val horaRef       = child.child("referenceTime").getValue(String::class.java)
                        ?: child.child("horario").getValue(String::class.java)
                    val startDate     = child.child("startDate").getValue(String::class.java)
                        ?: child.child("vigenciaDesde").getValue(String::class.java)
                    val endDate       = child.child("endDate").getValue(String::class.java)
                        ?: child.child("vigenciaHasta").getValue(String::class.java)
                    val alwaysAlert   = parseBoolean(child.child("alwaysAlert").value, true)
                    val highRisk      = parseBoolean(child.child("highRisk").value, false)
                    val notes         = child.child("notes").getValue(String::class.java) ?: ""
                    val active        = parseBoolean(child.child("active").value, true)
                    val updatedAt     = parseLong(child.child("updatedAt_ms").value, 0L)

                    val med = Medicamento(
                        serverId      = key,
                        nombre        = nombre,
                        dosis         = dosis,
                        intervalHours = intervalHours,
                        horaReferencia = horaRef,
                        vigenciaDesde = startDate,
                        vigenciaHasta = endDate,
                        siempreAvisar = alwaysAlert,
                        esAltoRiesgo  = highRisk,
                        notas         = notes,
                        activo        = active,
                        frecuencia    = "${intervalHours}h",
                        horarios      = emptyList(),
                        updatedAt_ms  = updatedAt
                    )
                    
                    list.add(med)
                    Log.d(TAG, "  ✅ [SYNC] Med[$key] '$nombre' (dosis=$dosis, activo=$active) importado")
                } catch (e: Exception) {
                    Log.w(TAG, "  ⚠ [SYNC] Error parseando medicamento: ${e.message}")
                }
            }
            
            Log.d(TAG, "📦 [SYNC] ✅ Total medicamentos descargados: ${list.size}")
            list
        } catch (e: Exception) {
            Log.e(TAG, "❌ [SYNC] Error descargando medicamentos: ${e.message}", e)
            emptyList()
        }
    }

    // ─── Conversion entidad → mapa Firebase ──────────────────────

    fun medToMap(m: Medicamento): Map<String, Any?> = mapOf(
        "name"          to m.nombre,
        "dose"          to m.dosis,
        "intervalHours" to m.intervalHours,
        "referenceTime" to (m.horaReferencia ?: ""),
        "startDate"     to (m.vigenciaDesde ?: ""),
        "endDate"       to (m.vigenciaHasta ?: ""),
        "alwaysAlert"   to m.siempreAvisar,
        "highRisk"      to m.esAltoRiesgo,
        "notes"         to m.notas,
        "active"        to m.activo,
        "updatedAt_ms"  to System.currentTimeMillis()
    )

    private fun parseInt(value: Any?, default: Int): Int {
        return when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: default
            else -> default
        }
    }

    private fun parseLong(value: Any?, default: Long): Long {
        return when (value) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull() ?: default
            else -> default
        }
    }

    private fun parseBoolean(value: Any?, default: Boolean): Boolean {
        return when (value) {
            is Boolean -> value
            is String -> value.equals("true", ignoreCase = true)
            is Number -> value.toInt() != 0
            else -> default
        }
    }
}
