package com.example.app_movile

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.graphics.Color
import android.os.Build
import android.os.StrictMode
import android.util.Log
import com.example.app_movile.notifications.NotificationManager as EventNotificationManager
import com.example.app_movile.util.Logger
import com.example.app_movile.util.ThemeManager

class SiagApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Diagnostic log
        Logger.d("DEB", "SiagApplication.onCreate start")

        // Activar StrictMode solo en debug para no generar DiskReadViolation en producción
        try {
            if (BuildConfig.DEBUG) {
                // Activar detecciones relevantes en hilo, pero evitar detectar sockets no etiquetados
                // (Firebase/Play Services usa hilos internos que pueden abrir sockets sin tag).
                StrictMode.setThreadPolicy(
                    StrictMode.ThreadPolicy.Builder()
                        .detectDiskReads()
                        .detectDiskWrites()
                        .detectCustomSlowCalls()
                        .penaltyLog()
                        .build()
                )
                StrictMode.setVmPolicy(
                    StrictMode.VmPolicy.Builder()
                        .detectAll()
                        .penaltyLog()
                        .build()
                )
            }
        } catch (t: Throwable) {
            Log.w("DEB", "No se pudo activar StrictMode: ${t.message}")
        }

        // Aplicar el tema global de forma no bloqueante ANTES de que cualquier Activity se cree
        // usando la versión asíncrona para evitar lecturas de disco en el hilo UI.
        ThemeManager.applyThemeAsync(this)

        // Precargar la instancia de la base de datos en background para evitar bloqueos posteriores
        try {
            Thread {
                try {
                    com.example.app_movile.data.db.AppDatabase.getInstance(applicationContext)
                    Logger.d("DEB", "AppDatabase precargada")
                } catch (t: Throwable) {
                    Log.w("DEB", "No se pudo precargar AppDatabase: ${t.message}")
                }
            }.start()
        } catch (t: Throwable) {
            Log.w("DEB", "Error iniciando precarga DB thread: ${t.message}")
        }

        // Crear canales de notificación clásicos y mejorados
        crearCanalesNotificacion()
        
        // Crear canales de notificación emergentes (caídas y ayuda)
        try {
            EventNotificationManager.createNotificationChannels(this)
            Logger.d("DEB", "Canales de notificación emergentes creados")
        } catch (e: Exception) {
            Log.e("DEB", "Error creando canales emergentes: ${e.message}")
        }
        
        Logger.d("DEB", "SiagApplication.onCreate end")
    }

    private fun crearCanalesNotificacion() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(NotificationManager::class.java)
        val canalEmergencia = NotificationChannel(
            CANAL_EMERGENCIA_ID,
            "Alertas de Caida",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notificaciones de emergencia cuando se detecta una caida"
            enableLights(true)
            lightColor = Color.RED
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(canalEmergencia)

        // Canal para recordatorios de medicamentos
        val canalSync = NotificationChannel(
            CANAL_SYNC_ID,
            "Servicio de sincronización",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Notificación silenciosa para mantener activo el servicio de sincronización"
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
            setShowBadge(false)
        }
        manager.createNotificationChannel(canalSync)

        val canalMedicamentos = NotificationChannel(
            "med_alerts",
            "Recordatorios de Medicamentos",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Recordatorios programados para tomar medicamentos"
            enableLights(true)
            lightColor = Color.CYAN
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 300, 200, 300)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(canalMedicamentos)
    }

    companion object {
        const val CANAL_EMERGENCIA_ID = "siag_canal_emergencia"
        const val CANAL_SYNC_ID = "siag_canal_sync"
    }
}
