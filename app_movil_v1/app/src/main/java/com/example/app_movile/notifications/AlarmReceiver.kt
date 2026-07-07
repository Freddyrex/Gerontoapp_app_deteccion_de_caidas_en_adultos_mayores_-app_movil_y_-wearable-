package com.example.app_movile.notifications

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.app_movile.R
import com.example.app_movile.util.PersonaManager

class AlarmReceiver : BroadcastReceiver() {
    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        val medId   = intent.getIntExtra("med_id", -1)
        val medName = intent.getStringExtra("med_name") ?: "Recordatorio"
        val medDose = intent.getStringExtra("med_dose") ?: ""
        val horaRef = intent.getStringExtra("hora_ref")

        val channelId = "med_alerts"

        // ── Personalizar con el nombre de la persona cuidada ────
        val nombrePersona = PersonaManager.getNombre(context)

        val title = if (nombrePersona.isNotBlank()) {
            context.getString(R.string.notif_medicamento_titulo, nombrePersona)
        } else {
            "Hora de medicamento"
        }

        val text = if (medDose.isNotEmpty()) {
            context.getString(R.string.notif_medicamento_body, medName, medDose)
        } else {
            medName
        }

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_medicamentos)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        if (!horaRef.isNullOrEmpty()) {
            builder.setSubText("Referencia: $horaRef")
        }

        try {
            with(NotificationManagerCompat.from(context)) {
                val nid = (medId.takeIf { it >= 0 } ?: (System.currentTimeMillis() and 0xffffffff).toInt())
                notify(nid, builder.build())
            }
        } catch (se: SecurityException) {
            // degradar silenciosamente si falta permiso runtime
        }
    }
}
