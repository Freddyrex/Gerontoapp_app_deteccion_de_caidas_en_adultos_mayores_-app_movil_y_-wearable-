package com.example.app_movile.wear

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.app_movile.R
import com.example.app_movile.SiagApplication
import com.example.app_movile.data.db.SiagDatabase
import com.example.app_movile.data.db.entities.EventoCaida
import com.example.app_movile.data.db.entities.SolicitudAyuda
import com.example.app_movile.data.wear.FirebaseFallbackSync
import com.example.app_movile.sync.SyncManager
import com.example.app_movile.ui.emergencia.EmergenciaActivity
import com.example.app_movile.util.PersonaManager
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.google.firebase.auth.FirebaseAuth
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * SiagWearListenerService — Escucha mensajes del reloj vía Wearable Data Layer.
 *
 * Rutas soportadas:
 *   /siag/alerta_caida   → evento de movimiento brusco / caída leve / caída grave
 *   /siag/llamado_ayuda  → llamado manual de ayuda presionado en el reloj
 *
 * Por cada evento:
 *   1. Guarda en Room (BD local).
 *   2. Publica en Firebase RTDB (fallEvents / helpRequests según corresponda).
 *   3. Muestra notificación de emergencia al cuidador.
 */
class SiagWearListenerService : WearableListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        const val RUTA_ALERTA_CAIDA  = "/siag/alerta_caida"
        const val RUTA_LLAMADO_AYUDA = "/siag/llamado_ayuda"
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val payload = String(messageEvent.data, Charsets.UTF_8)
        when (messageEvent.path) {
            RUTA_ALERTA_CAIDA  -> serviceScope.launch { procesarAlertaCaida(payload) }
            RUTA_LLAMADO_AYUDA -> serviceScope.launch { procesarLlamadoAyuda(payload) }
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  ALERTA DE CAÍDA
    // ─────────────────────────────────────────────────────────────

    private suspend fun procesarAlertaCaida(payloadJson: String) {
        val gson   = Gson()
        val alerta = gson.fromJson(payloadJson, AlertaCaidaPayload::class.java)
        val db     = SiagDatabase.getInstance(applicationContext)

        // Normalizar severidad al nuevo esquema de 3 tipos
        val severidad = when (alerta.severidad?.lowercase()) {
            "caida_grave", "grave"      -> "caida_grave"
            "caida_leve", "media"       -> "caida_leve"
            "movimiento_brusco", "leve" -> "movimiento_brusco"
            else                        -> "caida_leve"
        }

        // 1) Guardar en Room
        val evento = EventoCaida(
            timestamp           = alerta.timestamp,
            latitud             = alerta.latitud,
            longitud            = alerta.longitud,
            medicamentosActivos = gson.toJson(alerta.medicamentosActivos),
            codigoReloj         = alerta.codigoReloj ?: "",
            severidad           = severidad,
            estadoRespuesta     = "PENDIENTE"
        )
        val eventoId = db.eventoCaidaDao().insert(evento)

        // 2) Publicar en Firebase → fallEvents/{deviceId}/{pushId}
        val uid      = FirebaseAuth.getInstance().currentUser?.uid
        val deviceId = alerta.codigoReloj ?: "wearable"
        if (uid != null) {
            val personaId = PersonaManager.getPersonaId(applicationContext)
            FirebaseFallbackSync.publicarAlertaCaida(
                uid       = uid,
                deviceId  = deviceId,
                personaId = personaId,
                timestamp = alerta.timestamp,
                latitud   = alerta.latitud,
                longitud  = alerta.longitud,
                severidad = severidad
            )
        }

        // 3) Broadcast local para que la UI actualice inmediatamente
        try {
            applicationContext.sendBroadcast(
                Intent("com.example.app_movile.ACTION_LOCAL_CAIDA").apply {
                    putExtra("timestamp",   alerta.timestamp)
                    putExtra("latitud",     alerta.latitud)
                    putExtra("longitud",    alerta.longitud)
                    putExtra("codigoReloj", alerta.codigoReloj)
                    putExtra("severidad",   severidad)
                }
            )
        } catch (_: Exception) {}

        // 4) Comprobación diferida a 30s — si nadie respondió, re-notificar
        serviceScope.launch {
            try {
                kotlinx.coroutines.delay(30_000L)
                val saved = db.eventoCaidaDao().getById(eventoId.toInt())
                if (saved != null && saved.estadoRespuesta == "PENDIENTE") {
                    mostrarNotificacionSimple(
                        id    = (10000 + eventoId).toInt(),
                        title = "⚠️ Caída sin confirmar",
                        body  = "Se detectó una caída hace 30s y no hubo respuesta."
                    )
                    db.eventoCaidaDao().updateEstado(eventoId.toInt(), "NOTIFICADA")
                }
            } catch (e: Throwable) {
                android.util.Log.w("SiagWearListener", "checkDelayed: ${e.message}")
            }
        }

        // 5) Notificación y actividad de emergencia
        val nombrePersona     = PersonaManager.getNombre(applicationContext)
        var nombreDispositivo = deviceId
        try {
            val reloj = SyncManager.obtenerDispositivosVinculados(applicationContext)
                .firstOrNull { it.deviceId == deviceId }
            if (reloj?.nombreDispositivo?.isNotBlank() == true) {
                nombreDispositivo = reloj.nombreDispositivo
            }
        } catch (_: Exception) {}

        val titulo = if (nombrePersona.isNotBlank())
            applicationContext.getString(R.string.notif_caida_titulo, nombrePersona)
        else "⚠️ ALERTA DE CAÍDA DETECTADA"

        val cuerpo = applicationContext.getString(
            R.string.notif_caida_body, nombreDispositivo, severidad)

        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0,
            Intent(applicationContext, EmergenciaActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("latitud",   alerta.latitud)
                putExtra("longitud",  alerta.longitud)
                putExtra("timestamp", alerta.timestamp)
                putExtra("evento_id", eventoId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(applicationContext, SiagApplication.CANAL_EMERGENCIA_ID)
            .setSmallIcon(R.drawable.ic_alerta_caida)
            .setContentTitle(titulo)
            .setContentText(cuerpo)
            .setStyle(NotificationCompat.BigTextStyle().bigText(cuerpo))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(pendingIntent, true)
            .setAutoCancel(true)
            .build()

        notificar(1001, notif)
    }

    // ─────────────────────────────────────────────────────────────
    //  LLAMADO DE AYUDA
    // ─────────────────────────────────────────────────────────────

    private suspend fun procesarLlamadoAyuda(payloadJson: String) {
        val gson  = Gson()
        val ayuda = gson.fromJson(payloadJson, LlamadoAyudaPayload::class.java)
        val db    = SiagDatabase.getInstance(applicationContext)

        // 1) Guardar en Room (tabla solicitudes_ayuda)
        db.solicitudAyudaDao().insert(
            SolicitudAyuda(
                timestamp   = ayuda.timestamp,
                latitud     = ayuda.latitud ?: 0.0,
                longitud    = ayuda.longitud ?: 0.0,
                mensaje     = ayuda.mensaje ?: "Necesito asistencia",
                codigoReloj = ayuda.codigoReloj ?: "",
                status      = "pendiente"
            )
        )

        // 2) Publicar en Firebase → helpRequests/{deviceId}/{pushId}
        val uid      = FirebaseAuth.getInstance().currentUser?.uid
        val deviceId = ayuda.codigoReloj ?: "wearable"
        if (uid != null) {
            val personaId = PersonaManager.getPersonaId(applicationContext)
            FirebaseFallbackSync.publicarSolicitudAyuda(
                uid       = uid,
                deviceId  = deviceId,
                personaId = personaId,
                timestamp = ayuda.timestamp,
                latitud   = ayuda.latitud,
                longitud  = ayuda.longitud,
                mensaje   = ayuda.mensaje ?: "Necesito asistencia"
            )
        }

        // 3) Broadcast local para actualizar UI
        try {
            applicationContext.sendBroadcast(
                Intent("com.example.app_movile.ACTION_LLAMADO_AYUDA").apply {
                    putExtra("timestamp",   ayuda.timestamp)
                    putExtra("codigoReloj", ayuda.codigoReloj)
                    putExtra("mensaje",     ayuda.mensaje)
                }
            )
        } catch (_: Exception) {}

        // 4) Notificación de llamado de ayuda
        val nombrePersona = PersonaManager.getNombre(applicationContext)
        val titulo = if (nombrePersona.isNotBlank())
            "🆘 Llamado de ayuda: $nombrePersona" else "🆘 Llamado de ayuda"

        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 1002,
            Intent(applicationContext, EmergenciaActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("latitud", ayuda.latitud)
                putExtra("longitud", ayuda.longitud)
                putExtra("timestamp", ayuda.timestamp)
                putExtra("codigoReloj", ayuda.codigoReloj)
                putExtra("llamado_ayuda", true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cuerpo = ayuda.mensaje ?: "Necesito asistencia"
        val notif = NotificationCompat.Builder(applicationContext, SiagApplication.CANAL_EMERGENCIA_ID)
            .setSmallIcon(R.drawable.ic_alertas)
            .setContentTitle(titulo)
            .setContentText(cuerpo)
            .setStyle(NotificationCompat.BigTextStyle().bigText(cuerpo))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(pendingIntent, true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .build()

        notificar(1002, notif)
    }

    // ─────────────────────────────────────────────────────────────
    //  HELPERS
    // ─────────────────────────────────────────────────────────────

    private fun mostrarNotificacionSimple(id: Int, title: String, body: String) {
        val notif = NotificationCompat.Builder(applicationContext, SiagApplication.CANAL_EMERGENCIA_ID)
            .setSmallIcon(R.drawable.ic_alertas)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .build()
        notificar(id, notif)
    }

    private fun notificar(id: Int, notif: android.app.Notification) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(applicationContext).notify(id, notif)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}

// ─── Payloads ─────────────────────────────────────────────────

data class AlertaCaidaPayload(
    val timestamp: Long,
    val latitud: Double,
    val longitud: Double,
    val medicamentosActivos: List<MedicamentoWear> = emptyList(),
    val codigoReloj: String? = null,
    /** "movimiento_brusco" | "caida_leve" | "caida_grave" */
    val severidad: String? = null
)

data class LlamadoAyudaPayload(
    val timestamp: Long,
    val latitud: Double? = null,
    val longitud: Double? = null,
    val mensaje: String? = "Necesito asistencia",
    val codigoReloj: String? = null
)

data class MedicamentoWear(
    val nombre: String,
    val dosis: String,
    val esAltoRiesgo: Boolean
)
