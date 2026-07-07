package com.example.app_movile.ui.emergencia

import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.app_movile.R
import com.example.app_movile.util.ThemeManager

class EmergenciaActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val latitud = intent.getDoubleExtra("latitud", 0.0)
        val longitud = intent.getDoubleExtra("longitud", 0.0)
        val timestamp = intent.getLongExtra("timestamp", 0L)
        val isDarkMode = ThemeManager.isDark(this)

        val backgroundColor = ContextCompat.getColor(this, R.color.background_light)
        val cardColor = ContextCompat.getColor(this, R.color.surface_card)
        val textPrimary = ContextCompat.getColor(this, R.color.text_primary)
        val textSecondary = ContextCompat.getColor(this, R.color.text_secondary)
        val accentText = ContextCompat.getColor(this, R.color.accent_cyan)

        val padding = dp(24)
        val root = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(backgroundColor)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(padding, padding, padding, padding)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val cardDrawable = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(cardColor)
                setStroke(dp(1), ContextCompat.getColor(this@EmergenciaActivity, R.color.divider_gray))
            }
            background = cardDrawable
            setPadding(dp(20), dp(20), dp(20), dp(20))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(24)
            }
        }

        val titleView = TextView(this).apply {
            text = "Evento de caída"
            setTextColor(textPrimary)
            setTypeface(typeface, Typeface.BOLD)
            textSize = 20f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val timestampText = if (timestamp > 0L) {
            DateFormat.getDateFormat(this).format(timestamp) + " " + DateFormat.getTimeFormat(this).format(timestamp)
        } else {
            "Timestamp no disponible"
        }

        val infoText = TextView(this).apply {
            text = "Lat: $latitud\nLng: $longitud\nTs: $timestampText"
            setTextColor(textSecondary)
            textSize = 16f
            setLineSpacing(0f, 1.2f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
        }

        val helperText = TextView(this).apply {
            text = "Toca el botón para abrir la ubicación en Maps."
            setTextColor(accentText)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(14)
                bottomMargin = dp(8)
            }
        }

        val btnMaps = Button(this).apply {
            text = "VER UBICACIÓN EN GOOGLE MAPS"
            setTextColor(ContextCompat.getColor(this@EmergenciaActivity, R.color.button_text_primary))
            setBackgroundResource(R.drawable.bg_button_primary)
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6)
                bottomMargin = dp(10)
            }
            setOnClickListener {
                if (latitud != 0.0 && longitud != 0.0) {
                    val gmmIntentUri = Uri.parse("geo:$latitud,$longitud?q=$latitud,$longitud(Evento de caida)")
                    val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri).apply {
                        setPackage("com.google.android.apps.maps")
                    }
                    try {
                        startActivity(mapIntent)
                    } catch (e: Exception) {
                        startActivity(Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://www.google.com/maps/search/?api=1&query=$latitud,$longitud")
                        ))
                    }
                } else {
                    Toast.makeText(this@EmergenciaActivity, "Coordenadas no válidas", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val btnRegresar = Button(this).apply {
            text = "REGRESAR"
            setTextColor(textPrimary)
            setBackgroundResource(R.drawable.bg_button_secondary)
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { finish() }
        }

        card.addView(titleView)
        card.addView(infoText)
        card.addView(helperText)
        card.addView(btnMaps)
        card.addView(btnRegresar)

        container.addView(card)
        root.addView(container)

        setContentView(root)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
