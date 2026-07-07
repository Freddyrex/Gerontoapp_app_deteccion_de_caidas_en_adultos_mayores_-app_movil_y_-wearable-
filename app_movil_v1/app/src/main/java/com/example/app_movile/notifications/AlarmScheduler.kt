package com.example.app_movile.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

object AlarmScheduler {
    fun scheduleAlarm(context: Context, medId: Int, medName: String, medDose: String, triggerAtMillis: Long, horaRef: String?) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("med_id", medId)
            putExtra("med_name", medName)
            putExtra("med_dose", medDose)
            putExtra("hora_ref", horaRef)
        }
        val pi = PendingIntent.getBroadcast(context, medId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        try {
            // Prefer exact alarm when allowed (API 31+ has canScheduleExactAlarms)
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    if (am.canScheduleExactAlarms()) {
                        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
                        return
                    }
                } catch (t: Throwable) {
                    // If canScheduleExactAlarms not available or fails, fall through to try exact and catch SecurityException
                }
            }
            // Attempt to set exact alarm (may throw SecurityException on newer SDKs)
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        } catch (se: SecurityException) {
            Log.w("AlarmScheduler", "Exact alarm denied, falling back to inexact set(): ${se.message}")
            try {
                // Fallback to inexact alarm which doesn't require exact alarm permission
                am.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            } catch (t: Throwable) {
                Log.e("AlarmScheduler", "Failed to schedule alarm even with fallback: ${t.message}")
            }
        } catch (t: Throwable) {
            Log.e("AlarmScheduler", "Unexpected error scheduling alarm: ${t.message}")
        }
    }

    fun cancelAlarm(context: Context, medId: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java)
        val pi = PendingIntent.getBroadcast(context, medId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.cancel(pi)
    }
}
