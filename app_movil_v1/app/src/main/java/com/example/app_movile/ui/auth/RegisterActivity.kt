package com.example.app_movile.ui.auth

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.app_movile.R
import com.example.app_movile.auth.AuthManager
import com.example.app_movile.ui.main.MainActivity
import com.example.app_movile.ui.setup.SetupPersonaActivity
import com.example.app_movile.util.PersonaManager
import com.example.app_movile.util.ThemeManager
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class RegisterActivity : AppCompatActivity() {

    private lateinit var tilName: TextInputLayout
    private lateinit var tilEmail: TextInputLayout
    private lateinit var tilPassword: TextInputLayout
    private lateinit var tilPasswordRepeat: TextInputLayout
    private lateinit var tilCedula: TextInputLayout

    private lateinit var etName: TextInputEditText
    private lateinit var etEmail: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var etPasswordRepeat: TextInputEditText
    private lateinit var etCedula: TextInputEditText

    private lateinit var btnCreate: Button
    private lateinit var progress: ProgressBar
    private lateinit var btnThemeToggle: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_register)

        tilName = findViewById(R.id.tilName)
        tilEmail = findViewById(R.id.tilEmail)
        tilPassword = findViewById(R.id.tilPassword)
        tilPasswordRepeat = findViewById(R.id.tilPasswordRepeat)
        tilCedula = findViewById(R.id.tilCedula)

        etName = findViewById(R.id.etName)
        etEmail = findViewById(R.id.etEmail)
        etPassword = findViewById(R.id.etPassword)
        etPasswordRepeat = findViewById(R.id.etPasswordRepeat)
        etCedula = findViewById(R.id.etCedula)

        btnCreate = findViewById(R.id.btnCreate)
        progress = findViewById(R.id.progress)
        btnThemeToggle = findViewById(R.id.btnThemeToggle)

        btnThemeToggle.setOnClickListener {
            ThemeManager.toggleTheme(this)
            recreate()
        }

        btnCreate.setOnClickListener {
            clearErrors()
            val name = etName.text?.toString()?.trim() ?: ""
            val email = etEmail.text?.toString()?.trim() ?: ""
            val password = etPassword.text?.toString() ?: ""
            val password2 = etPasswordRepeat.text?.toString() ?: ""
            val cedula = etCedula.text?.toString()?.trim() ?: ""

            var hasError = false
            if (name.isBlank()) { tilName.error = getString(R.string.register_name_hint); hasError = true }
            if (email.isBlank()) { tilEmail.error = getString(R.string.register_email_hint); hasError = true }
            if (password.isBlank()) { tilPassword.error = getString(R.string.register_password_hint); hasError = true }
            if (password != password2) { tilPasswordRepeat.error = getString(R.string.register_password_mismatch); hasError = true }
            if (cedula.isBlank()) { tilCedula.error = getString(R.string.register_cedula_hint); hasError = true }
            if (hasError) return@setOnClickListener

            setUiBusy(true)

            // Ejecutar comprobación de email y registro en background para evitar bloqueos en UI
            Thread {
                // 1) comprobar si el email ya existe
                val already = AuthManager.isEmailRegistered(this, email)
                if (already) {
                    runOnUiThread {
                        setUiBusy(false)
                        tilEmail.error = getString(R.string.register_email_used)
                    }
                    return@Thread
                }

                // 2) intentar crear perfil
                val created = AuthManager.registerProfile(this, name, email, password, cedula)
                runOnUiThread {
                    setUiBusy(false)
                    if (!created) {
                        tilEmail.error = getString(R.string.register_failed)
                    } else {
                        val successMsg = getString(R.string.register_success)
                        Toast.makeText(this, successMsg, Toast.LENGTH_SHORT).show()
                        // Primera vez: ir a setup de persona
                        startActivity(Intent(this, SetupPersonaActivity::class.java))
                        finish()
                    }
                }
            }.start()
        }

        // Nuevo listener: ir a LoginActivity (buscar el botón en el layout y asignar listener si existe)
        findViewById<Button?>(R.id.btnLogin)?.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    private fun setUiBusy(busy: Boolean) {
        btnCreate.isEnabled = !busy
        findViewById<Button?>(R.id.btnLogin)?.isEnabled = !busy
        progress.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun clearErrors() {
        tilName.error = null
        tilEmail.error = null
        tilPassword.error = null
        tilPasswordRepeat.error = null
        tilCedula.error = null
    }
}
