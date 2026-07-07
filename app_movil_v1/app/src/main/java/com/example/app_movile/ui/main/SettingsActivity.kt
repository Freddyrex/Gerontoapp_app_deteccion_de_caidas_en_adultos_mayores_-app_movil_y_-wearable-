package com.example.app_movile.ui.main

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.app_movile.R
import com.example.app_movile.auth.AuthManager
import com.example.app_movile.util.PersonaManager
import com.example.app_movile.util.ThemeManager

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            // Usar color de fondo adaptativo al modo oscuro/claro
            val bgAttr = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.colorBackground, bgAttr, true)
            setBackgroundColor(bgAttr.data)
        }

        // ============ Header: botón de regresar + título ============
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            val marginsBottom = (16 * resources.displayMetrics.density).toInt()
            (layoutParams as LinearLayout.LayoutParams).bottomMargin = marginsBottom
        }

        val btnBack = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_revert)
            setBackgroundColor(Color.TRANSPARENT)
            val size = (40 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size)
            setOnClickListener { finish() }
        }

        val title = TextView(this).apply {
            text = getString(R.string.settings_title)
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(resources.getColor(R.color.text_primary, theme))
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins((12 * resources.displayMetrics.density).toInt(), 0, 0, 0)
            layoutParams = lp
        }

        header.addView(btnBack)
        header.addView(title)
        root.addView(header)

        // ============ BLOQUE 1: Tema Oscuro ============
        val containerDarkMode = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            val spacingBottom = (24 * resources.displayMetrics.density).toInt() // espacio negativo
            (layoutParams as LinearLayout.LayoutParams).bottomMargin = spacingBottom
        }

        val labelDarkMode = TextView(this).apply {
            text = getString(R.string.settings_dark_mode)
            textSize = 14f
            setTextColor(resources.getColor(R.color.text_primary, theme))
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = lp
        }

        val darkSwitch = SwitchCompat(this).apply {
            isChecked = ThemeManager.isDark(this@SettingsActivity)
            thumbTintList = ColorStateList.valueOf(
                if (isChecked) resources.getColor(R.color.accent_cyan, theme)
                else resources.getColor(R.color.divider_gray, theme)
            )
            setOnCheckedChangeListener { _, isChecked ->
                thumbTintList = ColorStateList.valueOf(
                    if (isChecked) resources.getColor(R.color.accent_cyan, theme)
                    else resources.getColor(R.color.divider_gray, theme)
                )
                ThemeManager.toggleTheme(this@SettingsActivity)
                recreate()
            }
        }

        containerDarkMode.addView(labelDarkMode)
        containerDarkMode.addView(darkSwitch)
        root.addView(containerDarkMode)

        // ============ BLOQUE 2: Cambiar Correo ============
        val sectionEmailLabel = TextView(this).apply {
            text = "Correo Electrónico"
            textSize = 12f
            setTextColor(resources.getColor(R.color.text_secondary, theme))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = (8 * resources.displayMetrics.density).toInt()
            lp.bottomMargin = (12 * resources.displayMetrics.density).toInt()
            layoutParams = lp
        }
        root.addView(sectionEmailLabel)

        val currentEmailTv = TextView(this).apply {
            text = getString(R.string.settings_current_email, AuthManager.getEmail(this@SettingsActivity) ?: "-")
            textSize = 13f
            setTextColor(resources.getColor(R.color.text_secondary, theme))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = (16 * resources.displayMetrics.density).toInt()
            layoutParams = lp
        }
        root.addView(currentEmailTv)

        val newEmailInput = crearCampoTextoLineal(
            context = this,
            hint = getString(R.string.settings_new_email_hint),
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        )
        root.addView(newEmailInput)

        val emailPwdInput = crearCampoTextoLineal(
            context = this,
            hint = getString(R.string.settings_password_hint),
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        root.addView(emailPwdInput)

        val btnChangeEmail = crearBotonPlano(
            context = this,
            text = getString(R.string.settings_change_email),
            isAction = false
        ).apply {
            setOnClickListener {
                val newEmail = newEmailInput.text?.toString()?.trim() ?: ""
                val pwd = emailPwdInput.text?.toString() ?: ""
                if (newEmail.isBlank() || pwd.isBlank()) {
                    Toast.makeText(this@SettingsActivity, getString(R.string.settings_fill_fields), Toast.LENGTH_SHORT).show()
                } else {
                    val ok = AuthManager.updateEmail(this@SettingsActivity, pwd, newEmail)
                    if (ok) {
                        Toast.makeText(this@SettingsActivity, getString(R.string.settings_email_updated), Toast.LENGTH_SHORT).show()
                        currentEmailTv.text = getString(R.string.settings_current_email, AuthManager.getEmail(this@SettingsActivity) ?: "-")
                        newEmailInput.text?.clear()
                        emailPwdInput.text?.clear()
                    } else {
                        Toast.makeText(this@SettingsActivity, getString(R.string.settings_email_update_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        val lp1 = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp1.bottomMargin = (24 * resources.displayMetrics.density).toInt()
        btnChangeEmail.layoutParams = lp1
        root.addView(btnChangeEmail)

        // ============ BLOQUE 3: Cambiar Contraseña ============
        val sectionPwdLabel = TextView(this).apply {
            text = "Contraseña"
            textSize = 12f
            setTextColor(resources.getColor(R.color.text_secondary, theme))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = (8 * resources.displayMetrics.density).toInt()
            lp.bottomMargin = (12 * resources.displayMetrics.density).toInt()
            layoutParams = lp
        }
        root.addView(sectionPwdLabel)

        val currentPwdInput = crearCampoTextoLineal(
            context = this,
            hint = getString(R.string.settings_current_password_hint),
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        root.addView(currentPwdInput)

        val newPwdInput = crearCampoTextoLineal(
            context = this,
            hint = getString(R.string.settings_new_password_hint),
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        root.addView(newPwdInput)

        val repeatNewPwdInput = crearCampoTextoLineal(
            context = this,
            hint = getString(R.string.settings_repeat_new_password_hint),
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        root.addView(repeatNewPwdInput)

        val btnChangePwd = crearBotonPlano(
            context = this,
            text = getString(R.string.settings_change_password),
            isAction = false
        ).apply {
            setOnClickListener {
                val curr = currentPwdInput.text?.toString() ?: ""
                val np = newPwdInput.text?.toString() ?: ""
                val rnp = repeatNewPwdInput.text?.toString() ?: ""
                if (curr.isBlank() || np.isBlank() || rnp.isBlank()) {
                    Toast.makeText(this@SettingsActivity, getString(R.string.settings_fill_fields), Toast.LENGTH_SHORT).show()
                } else if (np != rnp) {
                    Toast.makeText(this@SettingsActivity, getString(R.string.settings_password_mismatch), Toast.LENGTH_SHORT).show()
                } else {
                    val ok = AuthManager.updatePassword(this@SettingsActivity, curr, np)
                    if (ok) {
                        Toast.makeText(this@SettingsActivity, getString(R.string.settings_password_updated), Toast.LENGTH_SHORT).show()
                        currentPwdInput.text?.clear()
                        newPwdInput.text?.clear()
                        repeatNewPwdInput.text?.clear()
                    } else {
                        Toast.makeText(this@SettingsActivity, getString(R.string.settings_password_update_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        val lp2 = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp2.topMargin = (8 * resources.displayMetrics.density).toInt()
        lp2.bottomMargin = (32 * resources.displayMetrics.density).toInt()
        btnChangePwd.layoutParams = lp2
        root.addView(btnChangePwd)

        // ============ ACCIÓN: Vincular Reloj ============
        val btnVincular = crearBotonPlano(
            context = this,
            text = "Vincular Reloj",
            isAction = false
        ).apply {
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = (8 * resources.displayMetrics.density).toInt()
            lp.bottomMargin = (8 * resources.displayMetrics.density).toInt()
            layoutParams = lp
            setOnClickListener {
                startActivity(android.content.Intent(this@SettingsActivity, VinculacionActivity::class.java))
            }
        }
        root.addView(btnVincular)

        // ============ BLOQUE 4: Persona Cuidada ============
        root.addView(crearDivider())
        root.addView(crearSectionHeader(getString(R.string.settings_persona_titulo)))

        val nombreActual = PersonaManager.getNombre(this)
        val nivelActual  = PersonaManager.getNivel(this)

        root.addView(crearEtiqueta(getString(R.string.settings_persona_nombre_label)))
        val etPersonaNombre = crearCampoTextoLineal(this,
            hint = getString(R.string.setup_nombre_hint),
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        ).also { if (nombreActual.isNotBlank()) it.setText(nombreActual) }
        root.addView(etPersonaNombre)

        root.addView(crearEtiqueta(getString(R.string.settings_persona_nivel_label)))
        val nivelLabels = arrayOf(
            getString(R.string.setup_nivel_1_titulo),
            getString(R.string.setup_nivel_2_titulo),
            getString(R.string.setup_nivel_3_titulo)
        )
        val spinnerNivel = Spinner(this).apply {
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_item, nivelLabels)
                .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection((nivelActual - 1).coerceIn(0, 2), false)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = (16 * resources.displayMetrics.density).toInt()
            layoutParams = lp
        }
        root.addView(spinnerNivel)

        val btnGuardarPersona = crearBotonPlano(this, getString(R.string.settings_persona_guardar), false).apply {
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = (24 * resources.displayMetrics.density).toInt()
            layoutParams = lp
            setOnClickListener {
                val nuevoNombre = etPersonaNombre.text?.toString()?.trim() ?: ""
                val nuevoNivel  = spinnerNivel.selectedItemPosition + 1
                if (nuevoNombre.isBlank()) {
                    Toast.makeText(this@SettingsActivity, getString(R.string.setup_nombre_requerido), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                PersonaManager.guardarLocal(this@SettingsActivity, nuevoNombre, nuevoNivel)
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        PersonaManager.guardarEnFirebase(this@SettingsActivity, nuevoNombre, nuevoNivel)
                    }
                    Toast.makeText(this@SettingsActivity, getString(R.string.settings_persona_actualizado), Toast.LENGTH_SHORT).show()
                }
            }
        }
        root.addView(btnGuardarPersona)

        // ============ ACCIÓN CRÍTICA: Cerrar Sesión (Rojo, Plano) ============
        val btnLogout = crearBotonPlano(
            context = this,
            text = getString(R.string.logout_button),
            isAction = true // botón crítico en rojo
        ).apply {
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = (16 * resources.displayMetrics.density).toInt()
            layoutParams = lp
            setOnClickListener {
                androidx.appcompat.app.AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("Cerrar sesión")
                    .setMessage("¿Estás seguro de que deseas cerrar sesión?")
                    .setPositiveButton("Sí") { _, _ ->
                        AuthManager.logout(this@SettingsActivity)
                        val i = android.content.Intent(this@SettingsActivity, com.example.app_movile.ui.auth.LoginActivity::class.java)
                        startActivity(i)
                        finish()
                    }
                    .setNegativeButton("No", null)
                    .show()
            }
        }
        root.addView(btnLogout)

        val scroll = ScrollView(this).apply {
            addView(root)
        }
        setContentView(scroll)
    }

    private fun crearCampoTextoLineal(context: SettingsActivity, hint: String, inputType: Int): EditText {
        val isDark = ThemeManager.isDark(context)
        // Colores adaptativos al modo
        val inputBg  = if (isDark) android.graphics.Color.parseColor("#1AFFFFFF")
                       else android.graphics.Color.parseColor("#F0F4FF")
        val strokeClr = if (isDark) android.graphics.Color.parseColor("#33FFFFFF")
                        else resources.getColor(R.color.divider_gray, context.theme)
        val hintClr  = if (isDark) android.graphics.Color.parseColor("#80FFFFFF")
                       else resources.getColor(R.color.text_secondary, context.theme)
        val textClr  = if (isDark) android.graphics.Color.WHITE
                       else resources.getColor(R.color.text_primary, context.theme)

        return EditText(context).apply {
            this.hint = hint
            this.inputType = inputType
            textSize = 14f
            setHintTextColor(hintClr)
            setTextColor(textClr)
            val pad = (14 * resources.displayMetrics.density).toInt()
            setPadding(pad, (12 * resources.displayMetrics.density).toInt(), pad, (12 * resources.displayMetrics.density).toInt())
            setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
                setColor(inputBg)
                setStroke(
                    (1.5f * resources.displayMetrics.density).toInt(),
                    strokeClr
                )
                cornerRadius = (16 * resources.displayMetrics.density)
            })
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.bottomMargin = (16 * resources.displayMetrics.density).toInt()
            layoutParams = params
        }
    }

    private fun crearBotonPlano(context: SettingsActivity, text: String, isAction: Boolean): Button {
        val isDark = ThemeManager.isDark(context)
        val radius = (32 * resources.displayMetrics.density)
        // En dark mode los botones normales son cyan para que contrasten;
        // los de acción (logout) siempre rojos
        val btnBg = when {
            isAction -> resources.getColor(R.color.logout_red, context.theme)
            isDark   -> android.graphics.Color.parseColor("#00C2FF")  // cyan visible sobre oscuro
            else     -> resources.getColor(R.color.accent_black, context.theme)
        }
        val btnTextColor = when {
            isAction -> android.graphics.Color.WHITE
            isDark   -> android.graphics.Color.parseColor("#0D0E14")  // texto oscuro sobre cyan
            else     -> android.graphics.Color.WHITE
        }
        return Button(context).apply {
            this.text = text
            textSize = 14f
            setAllCaps(false)
            setTypeface(typeface, Typeface.BOLD)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(btnBg)
                cornerRadius = radius
            }
            setTextColor(btnTextColor)
            elevation = if (isAction) 0f else (3 * resources.displayMetrics.density)
            val h = (52 * resources.displayMetrics.density).toInt()
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
            params.setMargins(0, (6 * resources.displayMetrics.density).toInt(), 0, (6 * resources.displayMetrics.density).toInt())
            layoutParams = params
        }
    }

    private fun crearDivider() = android.view.View(this).apply {
        setBackgroundColor(resources.getColor(R.color.divider_gray, theme))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            (1 * resources.displayMetrics.density).toInt()).also {
            it.topMargin    = (16 * resources.displayMetrics.density).toInt()
            it.bottomMargin = (16 * resources.displayMetrics.density).toInt()
        }
    }

    private fun crearSectionHeader(texto: String) = TextView(this).apply {
        text = texto
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        letterSpacing = 0.08f
        setTextColor(resources.getColor(R.color.accent_cyan, theme))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.bottomMargin = (12 * resources.displayMetrics.density).toInt()
        }
    }

    private fun crearEtiqueta(texto: String) = TextView(this).apply {
        text = texto
        textSize = 12f
        setTextColor(resources.getColor(R.color.text_secondary, theme))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.bottomMargin = (4 * resources.displayMetrics.density).toInt()
        }
    }
}
