package com.example.app_movile.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import com.google.android.gms.tasks.Tasks
import java.util.*

object AuthManager {
    private const val PREFS_NAME = "siag_auth_prefs"
    private const val KEY_CURRENT_EMAIL = "current_email"
    private const val KEY_LOGGED_IN = "logged_in"

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun ensureFirebase(context: Context) {
        try {
            FirebaseApp.initializeApp(context)
        } catch (_: Exception) {
            // ignore if already initialized
        }
    }

    fun isLoggedIn(context: Context): Boolean {
        ensureFirebase(context)
        val auth = FirebaseAuth.getInstance()
        val user = auth.currentUser
        return user != null || prefs(context).getBoolean(KEY_LOGGED_IN, false)
    }

    fun logout(context: Context) {
        ensureFirebase(context)
        FirebaseAuth.getInstance().signOut()
        prefs(context).edit().putBoolean(KEY_LOGGED_IN, false).apply()
    }

    fun login(context: Context, email: String, password: String): Boolean {
        if (email.isBlank() || password.isBlank()) return false
        ensureFirebase(context)
        val auth = FirebaseAuth.getInstance()
        return try {
            val task = auth.signInWithEmailAndPassword(email, password)
            val result = Tasks.await<AuthResult>(task)
            val user = auth.currentUser ?: return false
            // store basic session info in prefs
            prefs(context).edit()
                .putBoolean(KEY_LOGGED_IN, true)
                .putString(KEY_CURRENT_EMAIL, user.email)
                .apply()
            true
        } catch (e: Exception) {
            Log.w("AuthManager", "login failed: ${e.message}")
            false
        }
    }

    /**
     * Comprueba de forma sincronizada si un email ya tiene métodos de sign-in (existe en Auth)
     */
    fun isEmailRegistered(context: Context, email: String): Boolean {
        if (email.isBlank()) return false
        ensureFirebase(context)
        return try {
            val auth = FirebaseAuth.getInstance()
            val task = auth.fetchSignInMethodsForEmail(email)
            val result = Tasks.await(task)
            val methods = result.signInMethods
            methods != null && methods.isNotEmpty()
        } catch (e: Exception) {
            Log.w("AuthManager", "isEmailRegistered check failed: ${e.message}")
            false
        }
    }

    fun registerProfile(context: Context, name: String, email: String, password: String, cedula: String): Boolean {
        if (email.isBlank() || password.isBlank()) return false
        ensureFirebase(context)

        if (isEmailRegistered(context, email)) {
            // Si el email ya existe, intentar iniciar sesión con la cuenta existente.
            return login(context, email, password)
        }

        val auth = FirebaseAuth.getInstance()
        val db = FirebaseDatabase.getInstance().reference
        return try {
            val task = auth.createUserWithEmailAndPassword(email, password)
            Tasks.await<AuthResult>(task)
            val user = auth.currentUser ?: return false

            // update display name
            try {
                val profileUpdates = UserProfileChangeRequest.Builder().setDisplayName(name).build()
                val up = user.updateProfile(profileUpdates)
                Tasks.await<Void>(up)
            } catch (e: Exception) {
                Log.e("AuthManager", "failed to update profile, deleting created user: ${e.message}")
                try { Tasks.await<Void>(user.delete()) } catch (_: Exception) {}
                // ensure sign out and prefs cleanup
                try { auth.signOut() } catch (_: Exception) {}
                prefs(context).edit().putBoolean(KEY_LOGGED_IN, false).apply()
                return false
            }

            // save profile in Realtime DB under /users/{uid}
            val profile = mapOf(
                "name" to name,
                "email" to email,
                "cedula" to cedula
            )
            try {
                val push = db.child("users").child(user.uid).setValue(profile)
                Tasks.await<Void>(push)
            } catch (e: Exception) {
                Log.e("AuthManager", "failed to write profile to RTDB, deleting created user: ${e.message}")
                try { Tasks.await<Void>(user.delete()) } catch (_: Exception) {}
                try { auth.signOut() } catch (_: Exception) {}
                prefs(context).edit().putBoolean(KEY_LOGGED_IN, false).apply()
                return false
            }

            prefs(context).edit()
                .putBoolean(KEY_LOGGED_IN, true)
                .putString(KEY_CURRENT_EMAIL, email)
                .apply()
            true
        } catch (e: Exception) {
            Log.w("AuthManager", "registerProfile failed: ${e.message}")
            false
        }
    }

    fun getName(context: Context): String? {
        ensureFirebase(context)
        val auth = FirebaseAuth.getInstance()
        return auth.currentUser?.displayName ?: prefs(context).getString(KEY_CURRENT_EMAIL, null)
    }

    fun getEmail(context: Context): String? {
        ensureFirebase(context)
        val auth = FirebaseAuth.getInstance()
        return auth.currentUser?.email ?: prefs(context).getString(KEY_CURRENT_EMAIL, null)
    }

    fun getCedula(context: Context): String? {
        // Try to read from Realtime DB (synchronously)
        ensureFirebase(context)
        val auth = FirebaseAuth.getInstance()
        val uid = auth.currentUser?.uid ?: return null
        return try {
            val snap = Tasks.await<DataSnapshot>(FirebaseDatabase.getInstance().reference.child("users").child(uid).get())
            snap.child("cedula").getValue(String::class.java)
        } catch (e: Exception) {
            null
        }
    }

    fun ensureUserProfile(context: Context): Boolean {
        ensureFirebase(context)
        val auth = FirebaseAuth.getInstance()
        val user = auth.currentUser ?: return false
        val uid = user.uid
        val email = user.email ?: return false
        val displayName = user.displayName ?: ""
        val db = FirebaseDatabase.getInstance().reference

        return try {
            val ref = db.child("users").child(uid)
            val snapshot = Tasks.await(ref.get())
            val profileUpdates = mutableMapOf<String, Any?>("email" to email)
            if (displayName.isNotBlank()) profileUpdates["name"] = displayName
            if (!snapshot.exists()) {
                Tasks.await(ref.updateChildren(profileUpdates))
            } else {
                if (snapshot.child("email").getValue(String::class.java).isNullOrBlank()) {
                    Tasks.await(ref.child("email").setValue(email))
                }
                if (snapshot.child("name").getValue(String::class.java).isNullOrBlank() && displayName.isNotBlank()) {
                    Tasks.await(ref.child("name").setValue(displayName))
                }
            }
            true
        } catch (e: Exception) {
            Log.w("AuthManager", "ensureUserProfile failed: ${e.message}")
            false
        }
    }

    fun updatePassword(context: Context, currentPassword: String, newPassword: String): Boolean {
        if (currentPassword.isBlank() || newPassword.isBlank()) return false
        ensureFirebase(context)
        val auth = FirebaseAuth.getInstance()
        val user = auth.currentUser ?: return false
        // Need to re-authenticate
        return try {
            val email = user.email ?: return false
            Tasks.await<AuthResult>(auth.signInWithEmailAndPassword(email, currentPassword))
            Tasks.await<Void>(user.updatePassword(newPassword))
            true
        } catch (_: Exception) { false }
    }

    fun updateEmail(context: Context, currentPassword: String, newEmail: String): Boolean {
        if (currentPassword.isBlank() || newEmail.isBlank()) return false
        ensureFirebase(context)
        val auth = FirebaseAuth.getInstance()
        val user = auth.currentUser ?: return false
        return try {
            val email = user.email ?: return false
            Tasks.await<AuthResult>(auth.signInWithEmailAndPassword(email, currentPassword))
            Tasks.await<Void>(user.updateEmail(newEmail))
            prefs(context).edit().putString(KEY_CURRENT_EMAIL, newEmail).apply()
            true
        } catch (_: Exception) { false }
    }

    // Permite marcar explícitamente el estado de sesión en SharedPreferences
    fun setLoggedIn(context: Context, loggedIn: Boolean, email: String? = null) {
        val editor = prefs(context).edit()
        editor.putBoolean(KEY_LOGGED_IN, loggedIn)
        if (email != null) editor.putString(KEY_CURRENT_EMAIL, email)
        editor.apply()
    }
}
