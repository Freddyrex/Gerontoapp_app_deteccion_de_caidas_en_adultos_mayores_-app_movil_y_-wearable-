package com.example.app_movile.ui.setup

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.lifecycle.lifecycleScope
import com.example.app_movile.R
import com.example.app_movile.ui.main.MainActivity
import com.example.app_movile.util.PersonaManager
import com.example.app_movile.util.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SetupPersonaActivity
 *
 * Pantalla de configuración inicial (una sola vez).
 * Se muestra inmediatamente después de un registro o primer login.
 * Permite configurar:
 *   1. Nombre de la persona cuidada
 *   2. Nivel de cuidado (1, 2 ó 3)
 *
 * Puede reabrirse desde Configuración → Persona cuidada.
 */
class SetupPersonaActivity : AppCompatActivity() {

    private var nivelSeleccionado: Int = 0
    private val cardNiveles = mutableListOf<CardView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Raíz con gradiente oscuro (se ve igual en modo claro y oscuro)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            val grad = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    resources.getColor(R.color.setup_bg_start, theme),
                    resources.getColor(R.color.setup_bg_end, theme)
                )
            )
            background = grad
            val pad = dp(24)
            setPadding(pad, dp(48), pad, pad)
        }

        val scroll = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // ── Encabezado ──────────────────────────────────────────
        val imgIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_person)
            imageTintList = android.content.res.ColorStateList.valueOf(
                resources.getColor(R.color.accent_cyan, theme)
            )
            val size = dp(72)
            layoutParams = LinearLayout.LayoutParams(size, size).also {
                it.gravity = Gravity.CENTER_HORIZONTAL
                it.bottomMargin = dp(24)
            }
        }
        content.addView(imgIcon)

        val tvTitulo = TextView(this).apply {
            text = getString(R.string.setup_titulo)
            textSize = 28f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(12) }
        }
        content.addView(tvTitulo)

        val tvSubtitulo = TextView(this).apply {
            text = getString(R.string.setup_subtitulo)
            textSize = 14f
            setTextColor(Color.parseColor("#B0BEC5"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(40) }
        }
        content.addView(tvSubtitulo)

        // ── Campo: Nombre ───────────────────────────────────────
        val tvNombreLabel = labelTexto(getString(R.string.setup_nombre_label))
        content.addView(tvNombreLabel)

        val etNombre = EditText(this).apply {
            hint = getString(R.string.setup_nombre_hint)
            textSize = 16f
            setBackgroundDrawable(GradientDrawable().apply {
                setColor(Color.parseColor("#22FFFFFF"))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), resources.getColor(R.color.accent_cyan, theme))
            })
            setHintTextColor(Color.parseColor("#80FFFFFF"))
            setTextColor(Color.WHITE)
            val pad = dp(16)
            setPadding(pad, dp(14), pad, dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(32) }
        }
        content.addView(etNombre)

        // ── Nivel de cuidado ────────────────────────────────────
        val tvNivelLabel = labelTexto(getString(R.string.setup_nivel_label))
        content.addView(tvNivelLabel)

        val nivelesData = listOf(
            Triple(1, getString(R.string.setup_nivel_1_titulo), getString(R.string.setup_nivel_1_desc)),
            Triple(2, getString(R.string.setup_nivel_2_titulo), getString(R.string.setup_nivel_2_desc)),
            Triple(3, getString(R.string.setup_nivel_3_titulo), getString(R.string.setup_nivel_3_desc))
        )
        val nivelColores = listOf(
            resources.getColor(R.color.nivel_1, theme),
            resources.getColor(R.color.nivel_2, theme),
            resources.getColor(R.color.nivel_3, theme)
        )
        val nivelEmojis = listOf("🟦", "🟧", "🟥")

        nivelesData.forEachIndexed { index, (nivel, titulo, desc) ->
            val card = CardView(this).apply {
                radius = dp(16).toFloat()
                cardElevation = dp(4).toFloat()
                setCardBackgroundColor(Color.parseColor("#1E2035"))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = dp(12) }
                isClickable = true
                isFocusable = true
            }

            val cardInner = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                val pad = dp(16)
                setPadding(pad, pad, pad, pad)
                gravity = Gravity.CENTER_VERTICAL
            }

            // Badge de número
            val badgeContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                val bg = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(nivelColores[index])
                }
                background = bg
                val size = dp(44)
                layoutParams = LinearLayout.LayoutParams(size, size).also {
                    it.marginEnd = dp(16)
                }
            }
            val tvNivelNum = TextView(this).apply {
                text = nivel.toString()
                textSize = 18f
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            badgeContainer.addView(tvNivelNum)

            val textsContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val tvTit = TextView(this).apply {
                text = titulo
                textSize = 15f
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = dp(4) }
            }
            val tvDesc = TextView(this).apply {
                text = desc
                textSize = 12f
                setTextColor(Color.parseColor("#B0BEC5"))
            }
            textsContainer.addView(tvTit)
            textsContainer.addView(tvDesc)

            cardInner.addView(badgeContainer)
            cardInner.addView(textsContainer)
            card.addView(cardInner)
            content.addView(card)
            cardNiveles.add(card)

            card.setOnClickListener {
                seleccionarNivel(nivel, nivelColores[index])
            }
        }

        // ── Espaciador ──────────────────────────────────────────
        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(24)
            )
        }
        content.addView(spacer)

        // ── Botón Continuar ─────────────────────────────────────
        val btnContinuar = Button(this).apply {
            text = getString(R.string.setup_continuar)
            textSize = 16f
            setAllCaps(false)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(resources.getColor(R.color.setup_bg_start, theme))
            background = GradientDrawable().apply {
                setColor(resources.getColor(R.color.accent_cyan, theme))
                cornerRadius = dp(28).toFloat()
            }
            elevation = dp(4).toFloat()
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)
            )
            layoutParams = lp
        }
        btnContinuar.setOnClickListener { intentarGuardar(etNombre) }
        content.addView(btnContinuar)

        scroll.addView(content)
        root.addView(scroll)
        setContentView(root)

        // Precargar si ya hay datos
        val nombreActual = PersonaManager.getNombre(this)
        if (nombreActual.isNotBlank()) etNombre.setText(nombreActual)

        val nivelActual = PersonaManager.getNivel(this)
        if (nivelActual in 1..3) seleccionarNivel(nivelActual, nivelColores[nivelActual - 1])
    }

    // ── Selección visual de nivel ───────────────────────────────

    private fun seleccionarNivel(nivel: Int, color: Int) {
        nivelSeleccionado = nivel
        cardNiveles.forEachIndexed { i, card ->
            val isSelected = (i + 1 == nivel)
            card.setCardBackgroundColor(
                if (isSelected) Color.parseColor("#1E2035") else Color.parseColor("#14161F")
            )
            // Borde de selección
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(if (isSelected) Color.parseColor("#1E2035") else Color.parseColor("#14161F"))
                cornerRadius = dp(16).toFloat()
                setStroke(
                    dp(2),
                    if (isSelected) color else Color.parseColor("#2E3050")
                )
            }
            card.foreground = if (isSelected) {
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(16).toFloat()
                    setStroke(dp(2), color)
                    setColor(Color.TRANSPARENT)
                }
            } else null
            card.setCardBackgroundColor(
                if (isSelected) Color.parseColor("#1E2B3A") else Color.parseColor("#14161F")
            )
        }
    }

    // ── Guardar y continuar ─────────────────────────────────────

    private fun intentarGuardar(etNombre: EditText) {
        val nombre = etNombre.text?.toString()?.trim() ?: ""
        if (nombre.isBlank()) {
            Toast.makeText(this, getString(R.string.setup_nombre_requerido), Toast.LENGTH_SHORT).show()
            return
        }
        if (nivelSeleccionado == 0) {
            Toast.makeText(this, getString(R.string.setup_nivel_requerido), Toast.LENGTH_SHORT).show()
            return
        }

        // Guardar en prefs inmediatamente
        PersonaManager.guardarLocal(this, nombre, nivelSeleccionado)
        PersonaManager.marcarSetupCompleto(this)

        // Guardar en Firebase en background
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                PersonaManager.guardarEnFirebase(this@SetupPersonaActivity, nombre, nivelSeleccionado)
            }
        }

        Toast.makeText(this, getString(R.string.setup_guardado), Toast.LENGTH_SHORT).show()

        // Ir a la pantalla principal
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    // ── Helpers ─────────────────────────────────────────────────

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun labelTexto(texto: String) = TextView(this).apply {
        text = texto
        textSize = 12f
        setTextColor(resources.getColor(R.color.accent_cyan, theme))
        setTypeface(typeface, Typeface.BOLD)
        letterSpacing = 0.1f
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = dp(8) }
    }
}
