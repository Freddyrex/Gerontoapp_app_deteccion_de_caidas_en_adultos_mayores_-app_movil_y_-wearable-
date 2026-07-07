package com.example.app_movile.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

object ThemeManager {
    private const val PREFS = "siag_theme_prefs"
    private const val KEY_DARK = "dark_mode"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isDark(context: Context): Boolean = prefs(context).getBoolean(KEY_DARK, false)

    fun toggleTheme(context: Context) {
        val next = !isDark(context)
        prefs(context).edit().putBoolean(KEY_DARK, next).apply()
        AppCompatDelegate.setDefaultNightMode(
            if (next) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    /**
     * Aplica el tema guardado en SharedPreferences.
     * Solo llama a setDefaultNightMode si el valor actual difiere del deseado,
     * evitando recreaciones de Activity innecesarias al inicio.
     *
     * Nota: la versión síncrona lee SharedPreferences en el hilo que se llame.
     * Para evitar lecturas de disco en el hilo UI (StrictMode), use applyThemeAsync desde Application.onCreate.
     */
    fun applyTheme(context: Context) {
        val desired = if (isDark(context)) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        if (AppCompatDelegate.getDefaultNightMode() != desired) {
            AppCompatDelegate.setDefaultNightMode(desired)
        }
    }

    /**
     * Versión no bloqueante: lee la preferencia en un hilo background y aplica el tema en el hilo principal.
     * Uso recomendado desde Application.onCreate para evitar DiskReadViolation en StrictMode.
     */
    fun applyThemeAsync(context: Context) {
        Thread {
            try {
                val desired = if (prefs(context).getBoolean(KEY_DARK, false)) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
                if (AppCompatDelegate.getDefaultNightMode() != desired) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        AppCompatDelegate.setDefaultNightMode(desired)
                    }
                }
            } catch (t: Throwable) {
                // No queremos romper el arranque por un fallo en lectura de prefs; loguear si es debug.
                android.util.Log.w("DEB", "applyThemeAsync failed: ${t.message}")
            }
        }.start()
    }
}
