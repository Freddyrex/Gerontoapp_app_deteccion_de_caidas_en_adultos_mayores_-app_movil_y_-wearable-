package com.example.app_movile.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import android.util.Log
import com.example.app_movile.R
import com.example.app_movile.ui.emergencia.EmergenciaActivity
import com.example.app_movile.ui.main.MainActivity
import java.text.SimpleDateFormat
import java.util.*

object NotificationManager {

    const val CHANNEL_FALLS = "falls_channel_v3" // Nueva versión para refrescar ajustes
    const val CHANNEL_HELP = "help_requests_channel_v3"
    const val CHANNEL_SYNC = "siag_canal_sync"
    private const val TAG = "NotificationMgr"

    private const val NOTIFICATION_ID_FALL_BASE = 1000
    private const val NOTIFICATION_ID_HELP_BASE = 2000
    const val NOTIFICATION_ID_SYNC_SERVICE = 800
    const val NOTIFICATION_ID_LISTENER_SERVICE = 801

    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // 1. Canal de Sincronización
            if (notificationManager.getNotificationChannel(CHANNEL_SYNC) == null) {
                val syncChannel = NotificationChannel(
                    CHANNEL_SYNC,
                    "Servicio de Monitoreo",
                    NotificationManager.IMPORTANCE_LOW
                )
                notificationManager.createNotificationChannel(syncChannel)
            }

            // 2. Canal para Caídas (Emergente + Ubicación)
            val fallChannel = NotificationChannel(
                CHANNEL_FALLS,
                "Alertas Urgentes de Caídas",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificaciones emergentes de caídas con ubicación"
                enableLights(true)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                val audioAttrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), audioAttrs)
            }

            // 3. Canal para Ayuda SOS (Emergente sin mapa)
            val helpChannel = NotificationChannel(
                CHANNEL_HELP,
                "Llamados de Ayuda SOS",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificaciones emergentes de auxilio"
                enableLights(true)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                val audioAttrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), audioAttrs)
            }

            notificationManager.createNotificationChannel(fallChannel)
            notificationManager.createNotificationChannel(helpChannel)
        }
    }

    fun buildForegroundServiceNotification(context: Context, title: String, text: String): android.app.Notification {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            launchIntent ?: Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_SYNC)
            .setSmallIcon(R.drawable.ic_alertas)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    /**
     * Muestra notificación de caída INCLUYENDO botón de ubicación.
     */
    fun showFallNotification(
        context: Context,
        deviceId: String,
        severidad: String,
        timestamp: Long,
        deviceName: String = "",
        personaName: String = "",
        lat: Double = 0.0,
        lng: Double = 0.0
    ) {
        val titulo = "⚠️ ALERTA: CAÍDA DETECTADA"
        val contenido = "Usuario: ${personaName.ifBlank { "Paciente" }}\nSeveridad: $severidad"

        val intent = Intent(context, EmergenciaActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("latitud", lat)
            putExtra("longitud", lng)
            putExtra("timestamp", timestamp)
            putExtra("codigoReloj", deviceId)
        }

        val pendingIntent = PendingIntent.getActivity(
            context, (deviceId + "fall").hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_FALLS)
            .setSmallIcon(R.drawable.ic_alerta_caida)
            .setContentTitle(titulo)
            .setContentText("Se ha detectado una caída crítica")
            .setStyle(NotificationCompat.BigTextStyle().bigText(contenido))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pendingIntent, true)
            .setAutoCancel(true)
            .setOngoing(true)

        // Solo añadir botón de mapa si hay coordenadas válidas
        if (lat != 0.0 && lng != 0.0) {
            val mapIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=$lat,$lng"))
            val mapPendingIntent = PendingIntent.getActivity(context, (deviceId + "map").hashCode(), mapIntent, PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(android.R.drawable.ic_dialog_map, "VER UBICACIÓN", mapPendingIntent)
        }

        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID_FALL_BASE + deviceId.hashCode(), builder.build())
        } catch (e: Exception) { Log.e(TAG, "Error notif caída: ${e.message}") }
    }

    /**
     * Muestra notificación de ayuda con coordenadas.
     */
    fun showHelpRequestNotification(
        context: Context,
        deviceId: String,
        timestamp: Long,
        deviceName: String = "",
        personaName: String = "",
        mensaje: String = "",
        lat: Double = 0.0,
        lng: Double = 0.0
    ) {
        val titulo = "🆘 SOLICITUD DE AYUDA"
        val contenido = "El usuario ${personaName.ifBlank { deviceName.ifBlank { deviceId } }} necesita asistencia.\nMensaje: $mensaje"

        val intent = Intent(context, EmergenciaActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("latitud", lat)
            putExtra("longitud", lng)
            putExtra("timestamp", timestamp)
            putExtra("codigoReloj", deviceId)
            putExtra("llamado_ayuda", true)
            putExtra("mensaje", mensaje)
        }

        val pendingIntent = PendingIntent.getActivity(
            context, (deviceId + "help").hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_HELP)
            .setSmallIcon(R.drawable.ic_alertas)
            .setContentTitle(titulo)
            .setContentText("Llamado SOS recibido")
            .setStyle(NotificationCompat.BigTextStyle().bigText(contenido))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pendingIntent, true)
            .setAutoCancel(true)
            .build()

        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID_HELP_BASE + deviceId.hashCode(), notification)
        } catch (e: Exception) { Log.e(TAG, "Error notif ayuda: ${e.message}") }
    }
}
