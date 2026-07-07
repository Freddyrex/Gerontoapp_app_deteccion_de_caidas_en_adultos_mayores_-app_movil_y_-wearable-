package com.example.app_movile.ui.auth

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.app_movile.R
import com.example.app_movile.auth.AuthManager
import com.example.app_movile.ui.main.MainActivity as HomeActivity
import com.example.app_movile.ui.setup.SetupPersonaActivity
import com.example.app_movile.util.PersonaManager
import com.example.app_movile.util.ThemeManager
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.AuthCredential
import android.util.Log
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.gms.common.SignInButton
import com.example.app_movile.sync.SyncService

class LoginActivity : AppCompatActivity() {

    private lateinit var etEmail: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var tilEmail: TextInputLayout
    private lateinit var tilPassword: TextInputLayout
    private lateinit var btnLogin: Button
    private lateinit var btnRegister: Button
    private lateinit var progress: ProgressBar
    private lateinit var btnThemeToggle: ImageButton
    private lateinit var btnGoogleSignIn: SignInButton
    private lateinit var googleClient: GoogleSignInClient
    private lateinit var firebaseAuth: FirebaseAuth
    private val TAG = "LoginActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "LoginActivity.onCreate start")

        // SIEMPRE inflar el layout primero para evitar pantalla en blanco
        try {
            setContentView(R.layout.activity_login)
            Log.d(TAG, "setContentView succeeded")
        } catch (e: Exception) {
            Log.e(TAG, "Error inflating activity_login.xml", e)
            // Fallback minimal para visualizar algo en pantalla y evitar pantalla en blanco
            val tv = TextView(this).apply {
                text = "Error inflating layout: ${e.message}"
                textSize = 18f
            }
            setContentView(tv)
            return
        }

        tilEmail = findViewById(R.id.tilEmail)
        tilPassword = findViewById(R.id.tilPassword)
        etEmail = findViewById(R.id.etEmail)
        etPassword = findViewById(R.id.etPassword)
        btnLogin = findViewById(R.id.btnLogin)
        btnRegister = findViewById(R.id.btnRegister)
        progress = findViewById(R.id.progress)
        btnThemeToggle = findViewById(R.id.btnThemeToggle)
        btnGoogleSignIn = findViewById(R.id.btnGoogleSignIn)

        Log.d(TAG, "Views initialized")

        // Si ya hay sesión activa, navegar DESPUÉS del layout para evitar pantalla blanca
        if (AuthManager.isLoggedIn(this)) {
            val dest = if (PersonaManager.isSetupDone(this)) HomeActivity::class.java else SetupPersonaActivity::class.java
            startActivity(Intent(this, dest))
            finish()
            return
        }

        btnThemeToggle.setOnClickListener {
            ThemeManager.toggleTheme(this)
            recreate() // reaplica el tema
        }

        btnLogin.setOnClickListener {
            clearErrors()
            val email = etEmail.text?.toString()?.trim() ?: ""
            val password = etPassword.text?.toString() ?: ""
            if (email.isBlank()) {
                tilEmail.error = getString(R.string.login_invalid)
                return@setOnClickListener
            }
            if (password.isBlank()) {
                tilPassword.error = getString(R.string.login_invalid)
                return@setOnClickListener
            }
            setUiBusy(true)
            // Sincronización en thread background
            Thread {
                try {
                    Log.d(TAG, "🔐 Iniciando login...")
                    val ok = AuthManager.login(this, email, password)
                    
                    if (ok) {
                        Log.d(TAG, "✅ Login exitoso, iniciando sincronización...")
                        runOnUiThread { iniciarSyncService() }
                        try {
                            // Llamar sincronización directamente (NO ES SUSPEND)
                            val okSync = com.example.app_movile.sync.SyncManager
                                .forzarSincronizacionCompleta(this@LoginActivity)
                            Log.d(TAG, "🔄 Resultado sincronización: $okSync")
                            if (okSync) {
                                Log.d(TAG, "✅ MEDICAMENTOS SINCRONIZADOS CORRECTAMENTE")
                            } else {
                                Log.w(TAG, "⚠️  Sincronización retornó false pero continuando...")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "⚠️  Error en sincronización: ${e.message}")
                            // No detener login si falla sincronización
                        }
                    } else {
                        Log.d(TAG, "❌ Login falló")
                    }
                    
                    runOnUiThread {
                        setUiBusy(false)
                        if (ok) {
                            val dest = if (PersonaManager.isSetupDone(this)) HomeActivity::class.java else SetupPersonaActivity::class.java
                            startActivity(Intent(this, dest))
                            finish()
                        } else {
                            Toast.makeText(this, getString(R.string.login_incorrect_credentials), Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error en login thread: ${e.message}", e)
                    runOnUiThread {
                        setUiBusy(false)
                        Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }

        btnRegister.setOnClickListener {
            // Lanzar RegisterActivity
            startActivity(Intent(this, RegisterActivity::class.java))
        }

        // Inicializar FirebaseAuth y GoogleSignIn
        firebaseAuth = FirebaseAuth.getInstance()
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        googleClient = GoogleSignIn.getClient(this, gso)

        // Diagnostics: log the generated resource values to help debug config issues
        try {
            val defaultWebClient = getString(R.string.default_web_client_id)
            val googleAppId = getString(resources.getIdentifier("google_app_id", "string", packageName))
            Log.d(TAG, "Google Sign-In config: default_web_client_id=$defaultWebClient, google_app_id=$googleAppId")
        } catch (e: Exception) {
            Log.w(TAG, "No se pudieron leer recursos de google-services.json: ${e.message}")
        }

        val googleSignInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            // Validar resultado
            if (result.resultCode != Activity.RESULT_OK || result.data == null) {
                Log.w(TAG, "Google sign-in canceled or no data: resultCode=${result.resultCode}")
                Toast.makeText(this, "Google sign-in cancelado o falló (sin datos)", Toast.LENGTH_SHORT).show()
                setUiBusy(false)
                return@registerForActivityResult
            }

            try {
                val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                val account = task.getResult(ApiException::class.java)
                if (account == null) {
                    Log.w(TAG, "GoogleSignInAccount es null")
                    Toast.makeText(this, getString(R.string.login_incorrect_credentials), Toast.LENGTH_SHORT).show()
                    setUiBusy(false)
                    return@registerForActivityResult
                }
                if (account.idToken == null) {
                    Log.w(TAG, "Google Sign-In returned null idToken. Check default_web_client_id and SHA-1 configuration in Firebase console.")
                    Toast.makeText(this, "Error de configuración de Google Sign-In. Verifica google-services.json y SHA-1 en Firebase.", Toast.LENGTH_LONG).show()
                    setUiBusy(false)
                    return@registerForActivityResult
                }
                // Auth with Firebase
                firebaseAuthWithGoogle(account)
            } catch (e: ApiException) {
                Log.w(TAG, "Google sign in failed: statusCode=${e.statusCode}", e)
                val message = when (e.statusCode) {
                    10 -> "DEVELOPER_ERROR: Verifica default_web_client_id y SHA-1 en Firebase Console"
                    12501 -> "El usuario canceló el flujo de Google Sign-In"
                    else -> "Google sign-in falló: ${e.statusCode}"
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                setUiBusy(false)
            }
        }

        btnGoogleSignIn.setOnClickListener {
            setUiBusy(true)
            // Sign out first to avoid stale sessions causing issues (helps select account each time)
            googleClient.signOut().addOnCompleteListener {
                val signInIntent = googleClient.signInIntent
                googleSignInLauncher.launch(signInIntent)
            }
        }
    }

    private fun setUiBusy(busy: Boolean) {
        btnLogin.isEnabled = !busy
        btnRegister.isEnabled = !busy
        btnGoogleSignIn.isEnabled = !busy
        progress.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun clearErrors() {
        tilEmail.error = null
        tilPassword.error = null
    }

    private fun firebaseAuthWithGoogle(acct: GoogleSignInAccount) {
        val credential: AuthCredential = GoogleAuthProvider.getCredential(acct.idToken, null)
        firebaseAuth.signInWithCredential(credential).addOnCompleteListener { task ->
            setUiBusy(false)
            if (task.isSuccessful) {
                // Firebase auth state updated; persist login in prefs, ensure profile and proceed to home
                val user = firebaseAuth.currentUser
                val email = user?.email
                AuthManager.setLoggedIn(this, true, email)
                Thread {
                    AuthManager.ensureUserProfile(this@LoginActivity)
                    runOnUiThread { iniciarSyncService() }
                }.start()
                val dest = if (PersonaManager.isSetupDone(this)) HomeActivity::class.java else SetupPersonaActivity::class.java
                startActivity(Intent(this, dest))
                finish()
            } else {
                Log.w(TAG, "Firebase signInWithCredential failed", task.exception)
                val message = when (task.exception) {
                    is FirebaseAuthUserCollisionException -> "El correo ya está registrado con otro método de inicio de sesión. Usa el login normal o vincula tu cuenta Google."
                    else -> getString(R.string.login_incorrect_credentials)
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun iniciarSyncService() {
        try {
            Log.i(TAG, "Iniciando SyncService desde LoginActivity")
            val intent = Intent(this, SyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error iniciando SyncService: ${e.message}", e)
        }
    }
}
