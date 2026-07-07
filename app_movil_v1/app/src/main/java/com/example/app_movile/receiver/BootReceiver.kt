package com.example.app_movile.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.app_movile.data.db.AppDatabase
import com.example.app_movile.notifications.AlarmScheduler
import com.example.app_movile.notifications.EventListenerService
import com.example.app_movile.sync.SyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.*

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("BootReceiver", "Intent recibido: ${intent.action}")
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED || intent?.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            try {
                val syncIntent = Intent(context, SyncService::class.java)
                val listenerIntent = Intent(context, EventListenerService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, syncIntent)
                    ContextCompat.startForegroundService(context, listenerIntent)
                } else {
                    context.startService(syncIntent)
                    context.startService(listenerIntent)
                }
            } catch (e: Exception) {
                Log.e("BootReceiver", "No se pudo iniciar servicio en boot: ${e.message}")
            }
        }

        // Reprogramar alarmas: obtener snapshot de medicamentos y programar la siguiente notificación
        val db = AppDatabase.getInstance(context)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val list = db.medicamentoDao().getAll().first()
                list.filter { it.activo }.forEach { med ->
                    val next = calculateNextTriggerMillis(med)
                    if (next > 0) {
                        AlarmScheduler.scheduleAlarm(context, med.id, med.nombre, med.dosis, next, med.horaReferencia)
                    }
                }
            } catch (e: Exception) {
                Log.e("BootReceiver", "Error reprogramando alarmas: ${e.message}")
            }
        }
    }

    fun calculateNextTriggerMillis(med: com.example.app_movile.data.db.entities.Medicamento): Long {
        try {
            val now = Calendar.getInstance()

            // Si hay hora de referencia, calcular siguiente disparo según intervalHours
            med.horaReferencia?.let { hr ->
                val parts = hr.split(":").mapNotNull { it.toIntOrNull() }
                if (parts.size == 2) {
                    val ref = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, parts[0])
                        set(Calendar.MINUTE, parts[1])
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    // Avanzar desde la hora de referencia hasta el próximo disparo futuro
                    val intervalMs = med.intervalHours * 3_600_000L
                    while (ref.timeInMillis <= now.timeInMillis) {
                        ref.timeInMillis = ref.timeInMillis + intervalMs
                    }
                    return ref.timeInMillis
                }
            }

            // Sin hora de referencia: disparar en intervalHours desde ahora
            if (med.intervalHours > 0) {
                return now.timeInMillis + med.intervalHours * 3_600_000L
            }
        } catch (_: Exception) {}
        return -1L
    }
}
