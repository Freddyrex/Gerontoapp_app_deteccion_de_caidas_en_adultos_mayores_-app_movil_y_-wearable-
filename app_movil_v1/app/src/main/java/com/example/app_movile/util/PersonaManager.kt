package com.example.app_movile.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.android.gms.tasks.Tasks

/**
 * PersonaManager
 *
 * Gestiona el perfil de la persona que se está cuidando.
 * Persiste en SharedPreferences (acceso offline) y sincroniza con Firebase RTDB.
 *
 * Datos almacenados:
 *   - nombre     : String  – Nombre de la persona cuidada
 *   - nivel      : Int     – 1 (básico), 2 (moderado), 3 (intensivo)
 *   - setupDone  : Boolean – Si ya se completó la pantalla de setup inicial
 */
object PersonaManager {

    private const val PREFS_NAME      = "siag_persona_prefs"
    private const val KEY_NOMBRE      = "persona_nombre"
    private const val KEY_NIVEL       = "persona_nivel"
    private const val KEY_SETUP       = "persona_setup_done"
    private const val KEY_PERSONA_ID  = "persona_id"
    private const val TAG             = "PersonaManager"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ─── Lectura ───────────────────────────────────────────────

    fun getNombre(ctx: Context): String =
        prefs(ctx).getString(KEY_NOMBRE, "") ?: ""

    fun getNivel(ctx: Context): Int =
        prefs(ctx).getInt(KEY_NIVEL, 1)

    fun isSetupDone(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SETUP, false)

    /**
     * Devuelve el personaId persistido (ej. "persona_1").
     * Si no hay ninguno guardado, construye uno a partir del UID del usuario autenticado.
     */
    fun getPersonaId(ctx: Context): String {
        val saved = prefs(ctx).getString(KEY_PERSONA_ID, "") ?: ""
        if (saved.isNotBlank()) return saved
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return ""
        val generated = "persona_${uid.takeLast(8)}"
        prefs(ctx).edit().putString(KEY_PERSONA_ID, generated).apply()
        return generated
    }

    fun setPersonaId(ctx: Context, personaId: String) {
        prefs(ctx).edit().putString(KEY_PERSONA_ID, personaId.trim()).apply()
    }

    // ─── Escritura local (SharedPreferences) ───────────────────

    fun guardarLocal(ctx: Context, nombre: String, nivel: Int) {
        prefs(ctx).edit()
            .putString(KEY_NOMBRE, nombre.trim())
            .putInt(KEY_NIVEL, nivel)
            .apply()
        Log.d(TAG, "Persona guardada localmente: nombre=$nombre nivel=$nivel")
    }

    fun marcarSetupCompleto(ctx: Context) {
        prefs(ctx).edit().putBoolean(KEY_SETUP, true).apply()
        Log.d(TAG, "Setup de persona marcado como completo")
    }

    fun resetSetup(ctx: Context) {
        prefs(ctx).edit().putBoolean(KEY_SETUP, false).apply()
    }

    // ─── Sincronización con Firebase ──────────────────────────

    /**
     * Guarda el perfil en Firebase RTDB bajo /users/{uid}/persona/
     * y actualiza el flag de setup.
     * Debe llamarse en un hilo de IO (Tasks.await).
     */
    fun guardarEnFirebase(ctx: Context, nombre: String, nivel: Int): Boolean {
        return try {
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return false
            val db  = FirebaseDatabase.getInstance().reference
            val data = mapOf(
                "nombre" to nombre.trim(),
                "nivel"  to nivel
            )
            Tasks.await(db.child("users").child(uid).child("persona").setValue(data))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error guardando persona en Firebase: ${e.message}")
            false
        }
    }

    /**
     * Lee el perfil desde Firebase y actualiza SharedPreferences.
     * Usar en background.
     */
    fun cargarDesdeFirebase(ctx: Context): Boolean {
        return try {
            val uid  = FirebaseAuth.getInstance().currentUser?.uid ?: return false
            val db   = FirebaseDatabase.getInstance().reference
            val snap = Tasks.await(db.child("users").child(uid).child("persona").get())
            if (!snap.exists()) return false
            val nombre = snap.child("nombre").getValue(String::class.java) ?: return false
            val nivel  = (snap.child("nivel").getValue(Long::class.java) ?: 1L).toInt()
            guardarLocal(ctx, nombre, nivel)
            Log.d(TAG, "Persona cargada desde Firebase: $nombre / nivel $nivel")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error cargando persona desde Firebase: ${e.message}")
            false
        }
    }
}
