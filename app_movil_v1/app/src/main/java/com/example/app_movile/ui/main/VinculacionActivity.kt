package com.example.app_movile.ui.main

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.lifecycle.lifecycleScope
import com.example.app_movile.R
import com.example.app_movile.sync.Dispositivo
import com.example.app_movile.sync.SyncManager
import com.example.app_movile.sync.WatchBridgeManager
import com.example.app_movile.util.PersonaManager
import com.example.app_movile.util.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * VinculacionActivity – Pantalla de emparejamiento de relojes.
 *
 * Mejoras v2:
 *  - Animación de radar/pulso circular durante el proceso de vinculación.
 *  - Campo para nombre del dispositivo.
 *  - Asignación automática del nombre de la persona desde PersonaManager.
 *  - Tarjetas de relojes vinculados mejoradas con nombre de persona y dispositivo.
 *  - Soporte escalable para múltiples relojes.
 */
class VinculacionActivity : AppCompatActivity() {

    private val deviceIdTelefono: String by lazy {
        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "telefono_default"
    }

    // Animadores del radar
    private val radarAnimators = mutableListOf<ObjectAnimator>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val isDark = ThemeManager.isDark(this)
        val bgColor = if (isDark) resources.getColor(R.color.bottom_bar_bg, theme)
                      else resources.getColor(R.color.background_light, theme)
        val textPrimary = if (isDark) resources.getColor(R.color.white, theme)
                          else resources.getColor(R.color.text_primary, theme)
        val textSecondary = resources.getColor(R.color.text_secondary, theme)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(bgColor)
        }

        // ── Header ──────────────────────────────────────────────
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(dp(16), dp(16), dp(16), dp(8))
            setBackgroundColor(bgColor)
        }
        val btnBack = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_revert)
            setBackgroundColor(Color.TRANSPARENT)
            imageTintList = android.content.res.ColorStateList.valueOf(textPrimary)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setOnClickListener { finish() }
        }
        val tvTitle = TextView(this).apply {
            text = getString(R.string.vinculacion_title)
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textPrimary)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .also { it.marginStart = dp(12) }
        }
        header.addView(btnBack)
        header.addView(tvTitle)
        root.addView(header)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(dp(20), dp(8), dp(20), dp(24))
        }

        // ── Animación Radar ──────────────────────────────────────
        val radarContainer = buildRadarAnimation()
        content.addView(radarContainer)

        // ── Instrucciones ────────────────────────────────────────
        content.addView(tv(getString(R.string.vinculacion_instructions), 14f, textSecondary, bottomMg = dp(24)))

        // ── Campo: código 3 dígitos ──────────────────────────────
        content.addView(tv(getString(R.string.vinculacion_codigo_label), 12f,
            resources.getColor(R.color.accent_cyan, theme), bold = true, bottomMg = dp(8)))

        val inputCodigo = editField(getString(R.string.vinculacion_code_hint), InputType.TYPE_CLASS_NUMBER, textPrimary, textSecondary)
        inputCodigo.textSize = 28f
        inputCodigo.gravity  = Gravity.CENTER
        content.addView(inputCodigo)

        content.addView(tv(getString(R.string.vinculacion_info_text), 12f, textSecondary, topMg = dp(6), bottomMg = dp(24)))

        // ── Campo: Nombre del dispositivo ────────────────────────
        content.addView(tv(getString(R.string.vinculacion_nombre_dispositivo_label), 12f,
            resources.getColor(R.color.accent_cyan, theme), bold = true, bottomMg = dp(8)))

        val inputNombreDispositivo = editField(
            getString(R.string.vinculacion_nombre_dispositivo_hint),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, textPrimary, textSecondary
        )
        content.addView(inputNombreDispositivo)

        // ── Progreso de conexión ─────────────────────────────────
        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            progressTintList = android.content.res.ColorStateList.valueOf(resources.getColor(R.color.accent_cyan, theme))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4))
                .also { it.topMargin = dp(16); it.bottomMargin = dp(8) }
        }
        content.addView(progressBar)

        val tvEstado = TextView(this).apply {
            text = ""
            textSize = 13f
            setTextColor(textSecondary)
            gravity = Gravity.CENTER
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .also { it.bottomMargin = dp(16) }
        }
        content.addView(tvEstado)

        // ── Botón: Vincular reloj ────────────────────────────────
        val btnVincular = accentButton(getString(R.string.vinculacion_button_link))
        btnVincular.layoutParams = (btnVincular.layoutParams as LinearLayout.LayoutParams)
            .also { it.bottomMargin = dp(12) }
        btnVincular.setOnClickListener {
            val codigo = inputCodigo.text?.toString()?.trim() ?: ""
            val nombre = inputNombreDispositivo.text?.toString()?.trim() ?: ""
            if (codigo.length != 3 || !codigo.all { it.isDigit() }) {
                Toast.makeText(this, getString(R.string.vinculacion_invalid_code), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (nombre.isBlank()) {
                Toast.makeText(this, getString(R.string.vinculacion_campos_requeridos), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            btnVincular.isEnabled = false
            progressBar.visibility = View.VISIBLE
            tvEstado.text = getString(R.string.vinculacion_buscando)
            tvEstado.visibility = View.VISIBLE
            startRadarAnimation()
            lifecycleScope.launch {
                tvEstado.text = getString(R.string.vinculacion_conectando)
                val ok = withContext(Dispatchers.IO) {
                    try { WatchBridgeManager(this@VinculacionActivity).confirmarVinculacionConReloj(codigo) }
                    catch (e: Exception) { false }
                }
                if (ok) {
                    // guardar nombre del dispositivo y persona
                    val nombrePersona = PersonaManager.getNombre(this@VinculacionActivity)
                    withContext(Dispatchers.IO) {
                        SyncManager.actualizarNombreDispositivo(this@VinculacionActivity, codigo, nombre, nombrePersona)
                    }
                    stopRadarAnimation()
                    progressBar.visibility = View.GONE
                    tvEstado.visibility = View.GONE
                    Toast.makeText(this@VinculacionActivity, getString(R.string.vinculacion_linked_success), Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    stopRadarAnimation()
                    progressBar.visibility = View.GONE
                    tvEstado.text = ""
                    tvEstado.visibility = View.GONE
                    btnVincular.isEnabled = true
                    Toast.makeText(this@VinculacionActivity, getString(R.string.vinculacion_link_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
        content.addView(btnVincular)

        // ── Botón: Mi código ─────────────────────────────────────
        val btnMiCodigo = secondaryButton(getString(R.string.vinculacion_button_mycode))
        btnMiCodigo.setOnClickListener { generarYMostrarCodigo() }
        content.addView(btnMiCodigo)

        // ── Separador ────────────────────────────────────────────
        content.addView(separador())

        // ── Relojes vinculados ───────────────────────────────────
        content.addView(tv("Relojes vinculados", 15f, textPrimary, bold = true, topMg = dp(16), bottomMg = dp(12)))

        val listaDispositivos = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        content.addView(listaDispositivos)

        cargarDispositivosVinculados(listaDispositivos)

        scroll.addView(content)
        root.addView(scroll)
        setContentView(root)
    }

    // ── Animación Radar ──────────────────────────────────────────

    private fun buildRadarAnimation(): FrameLayout {
        val container = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(180))
                .also { it.topMargin = dp(8); it.bottomMargin = dp(16) }
        }

        val centerSize = dp(64)
        // Anillos del radar (3)
        for (i in 0 until 3) {
            val ring = View(this).apply {
                val s = centerSize + dp(32 + i * 28)
                layoutParams = FrameLayout.LayoutParams(s, s).also {
                    it.gravity = Gravity.CENTER
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setStroke(dp(2), resources.getColor(R.color.accent_cyan, theme))
                    setColor(Color.TRANSPARENT)
                }
                alpha = (0.5f - i * 0.15f).coerceAtLeast(0.05f)
            }
            container.addView(ring)

            // Animador de escala + fade
            val scaleX = ObjectAnimator.ofFloat(ring, "scaleX", 0.6f, 1.15f, 1f).apply {
                duration = 2000L + i * 400L
                repeatMode = ValueAnimator.RESTART
                repeatCount = ValueAnimator.INFINITE
                startDelay = i * 500L
                interpolator = AccelerateDecelerateInterpolator()
            }
            val scaleY = ObjectAnimator.ofFloat(ring, "scaleY", 0.6f, 1.15f, 1f).apply {
                duration = 2000L + i * 400L
                repeatMode = ValueAnimator.RESTART
                repeatCount = ValueAnimator.INFINITE
                startDelay = i * 500L
                interpolator = AccelerateDecelerateInterpolator()
            }
            val alpha = ObjectAnimator.ofFloat(ring, "alpha", 0.7f, 0.1f, 0.5f).apply {
                duration = 2000L + i * 400L
                repeatMode = ValueAnimator.RESTART
                repeatCount = ValueAnimator.INFINITE
                startDelay = i * 500L
            }
            radarAnimators.addAll(listOf(scaleX, scaleY, alpha))
        }

        // Ícono de reloj central
        val centerCircle = ImageView(this).apply {
            setImageResource(R.drawable.ic_watch)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(resources.getColor(R.color.accent_cyan, theme))
            }
            background = bg
            val pad = dp(14)
            setPadding(pad, pad, pad, pad)
            layoutParams = FrameLayout.LayoutParams(centerSize, centerSize).also {
                it.gravity = Gravity.CENTER
            }
        }
        container.addView(centerCircle)

        // Animación de pulso en el círculo central
        val pulse = ObjectAnimator.ofFloat(centerCircle, "scaleX", 1f, 1.12f, 1f).apply {
            duration = 1200L
            repeatMode = ValueAnimator.RESTART
            repeatCount = ValueAnimator.INFINITE
        }
        val pulseY = ObjectAnimator.ofFloat(centerCircle, "scaleY", 1f, 1.12f, 1f).apply {
            duration = 1200L
            repeatMode = ValueAnimator.RESTART
            repeatCount = ValueAnimator.INFINITE
        }
        radarAnimators.addAll(listOf(pulse, pulseY))

        // Iniciar animaciones
        // radarAnimators.forEach { it.start() }

        return container
    }

    private fun startRadarAnimation() {
        radarAnimators.forEach { if (!it.isRunning) it.start() }
    }

    private fun stopRadarAnimation() {
        radarAnimators.forEach { it.cancel() }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRadarAnimation()
    }

    override fun onStart() {
        super.onStart()
        // Iniciar animaciones cuando la Activity entra en foreground
        startRadarAnimation()
    }

    override fun onStop() {
        super.onStop()
        // Detener animaciones para liberar recursos
        stopRadarAnimation()
    }

    // ── Lógica ──────────────────────────────────────────────────

    private fun generarYMostrarCodigo() {
        lifecycleScope.launch(Dispatchers.Main) {
            try {
                val codigo = SyncManager.generarCodigoVinculacion()
                val ok = withContext(Dispatchers.IO) {
                    SyncManager.registrarCodigoPendiente(this@VinculacionActivity, codigo, "reloj", deviceIdTelefono)
                }
                if (ok) {
                    AlertDialog.Builder(this@VinculacionActivity)
                        .setTitle(getString(R.string.vinculacion_mycode_title))
                        .setMessage(getString(R.string.vinculacion_mycode_message, codigo))
                        .setPositiveButton("OK", null)
                        .show()
                } else {
                    Toast.makeText(this@VinculacionActivity, getString(R.string.vinculacion_error_generating), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@VinculacionActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun cargarDispositivosVinculados(contenedor: LinearLayout) {
        lifecycleScope.launch(Dispatchers.Main) {
            try {
                val dispositivos = withContext(Dispatchers.IO) {
                    SyncManager.obtenerDispositivosVinculados(this@VinculacionActivity)
                }
                contenedor.removeAllViews()
                val relojes = dispositivos.filter { it.tipo == "reloj" }
                if (relojes.isEmpty()) {
                    contenedor.addView(tv(getString(R.string.vinculacion_sin_relojes), 13f,
                        resources.getColor(R.color.text_secondary, theme)))
                } else {
                    relojes.forEach { rel -> contenedor.addView(tarjetaReloj(rel)) }
                }
            } catch (e: Exception) {
                contenedor.addView(tv("No se pudo cargar la lista", 13f,
                    resources.getColor(R.color.text_secondary, theme)))
            }
        }
    }

    private fun tarjetaReloj(d: Dispositivo): CardView {
        val sdf    = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        val activo = d.estado == "vinculado" || d.estado == "activo"
        val estadoColor = if (activo) Color.parseColor("#43A047") else Color.parseColor("#E53935")

        val card = CardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = dp(3).toFloat()
            setCardBackgroundColor(resources.getColor(R.color.surface_card, theme))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .also { it.bottomMargin = dp(12) }
        }

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
        }

        // Fila superior: ícono + nombre dispositivo + chip estado
        val fila1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .also { it.bottomMargin = dp(8) }
        }

        val watchIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_watch)
            imageTintList = android.content.res.ColorStateList.valueOf(resources.getColor(R.color.accent_cyan, theme))
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).also { it.marginEnd = dp(12) }
        }

        val tvNombre = TextView(this).apply {
            text = if (d.nombreDispositivo.isNotBlank()) d.nombreDispositivo else "Reloj · ${d.deviceId}"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(resources.getColor(R.color.text_primary, theme))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        // Chip de estado
        val chipEstado = TextView(this).apply {
            text = if (activo) "● Activo" else "● Offline"
            textSize = 11f
            setTextColor(estadoColor)
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(if (activo) Color.parseColor("#1A43A047") else Color.parseColor("#1AE53935"))
                cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }

        fila1.addView(watchIcon)
        fila1.addView(tvNombre)
        fila1.addView(chipEstado)

        // Persona asignada
        if (d.nombrePersona.isNotBlank()) {
            inner.addView(fila1)
            inner.addView(tv("👤 ${d.nombrePersona}", 13f,
                resources.getColor(R.color.accent_cyan, theme), bottomMg = dp(4)))
        } else {
            inner.addView(fila1)
        }

        inner.addView(tv("🔑 Código: ${d.deviceId}", 12f,
            resources.getColor(R.color.text_secondary, theme), bottomMg = dp(2)))
        inner.addView(tv("⏱️ Sync: ${sdf.format(Date(d.ultimaSincronizacion))}", 11f,
            resources.getColor(R.color.text_secondary, theme)))

        card.addView(inner)
        return card
    }

    // ── Helpers UI ───────────────────────────────────────────────

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(
        text: String, size: Float, color: Int,
        bold: Boolean = false, topMg: Int = 0, bottomMg: Int = 0
    ) = TextView(this).apply {
        this.text = text
        textSize  = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .also { it.topMargin = topMg; it.bottomMargin = bottomMg }
    }

    private fun editField(hint: String, inputType: Int, textColor: Int, hintColor: Int) =
        EditText(this).apply {
            this.hint      = hint
            this.inputType = inputType
            textSize       = 16f
            setTextColor(textColor)
            setHintTextColor(hintColor)
            background = GradientDrawable().apply {
                setStroke(dp(1), resources.getColor(R.color.divider_gray, theme))
                cornerRadius = dp(12).toFloat()
                setColor(Color.TRANSPARENT)
            }
            val pad = dp(14)
            setPadding(pad, dp(12), pad, dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .also { it.bottomMargin = dp(8) }
        }

    private fun accentButton(text: String) = Button(this).apply {
        this.text = text
        textSize  = 14f
        setAllCaps(false)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(resources.getColor(R.color.setup_bg_start, theme))
        background = GradientDrawable().apply {
            setColor(resources.getColor(R.color.accent_cyan, theme))
            cornerRadius = dp(28).toFloat()
        }
        elevation = dp(2).toFloat()
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
            .also { it.topMargin = dp(8) }
    }

    private fun secondaryButton(text: String) = Button(this).apply {
        this.text = text
        textSize  = 14f
        setAllCaps(false)
        setTextColor(resources.getColor(R.color.text_primary, theme))
        background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(dp(1), resources.getColor(R.color.divider_gray, theme))
            cornerRadius = dp(28).toFloat()
        }
        elevation = 0f
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
            .also { it.topMargin = dp(8); it.bottomMargin = dp(8) }
    }

    private fun separador() = View(this).apply {
        setBackgroundColor(resources.getColor(R.color.divider_gray, theme))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            .also { it.topMargin = dp(8); it.bottomMargin = dp(8) }
    }
}
