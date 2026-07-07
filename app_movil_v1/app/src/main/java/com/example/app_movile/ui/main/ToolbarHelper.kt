package com.example.app_movile.ui.main

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.example.app_movile.R
import com.example.app_movile.util.ThemeManager

/**
 * ToolbarHelper — fuente unica de verdad para el header de todas las pantallas.
 * Garantiza que MainActivity y AlertasActivity tengan EXACTAMENTE el mismo toolbar.
 *
 * Colores:
 *   Modo claro → fondo #C8C8C8 (gris visible), texto #0D0E14 (negro)
 *   Modo oscuro → fondo #0D0E14 (negro),        texto #FFFFFF  (blanco)
 * Altura fija: 56dp (estandar Material Design)
 */
object ToolbarHelper {

    fun buildHeader(
        activity: AppCompatActivity,
        title: String,
        showBack: Boolean = false,
        onBack: (() -> Unit)? = null,
        extraView: android.view.View? = null
    ): LinearLayout {
        val isDark = when (AppCompatDelegate.getDefaultNightMode()) {
            AppCompatDelegate.MODE_NIGHT_YES -> true
            AppCompatDelegate.MODE_NIGHT_NO -> false
            else -> when (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
                Configuration.UI_MODE_NIGHT_YES -> true
                Configuration.UI_MODE_NIGHT_NO -> false
                else -> ThemeManager.isDark(activity)
            }
        }
        val bgColor = if (isDark)
            activity.resources.getColor(R.color.toolbar_dark, activity.theme)
        else
            activity.resources.getColor(R.color.toolbar_light, activity.theme)
        val txtColor = if (isDark)
            activity.resources.getColor(R.color.white, activity.theme)
        else
            activity.resources.getColor(R.color.text_primary, activity.theme)
        val dp = { v: Int -> (v * activity.resources.displayMetrics.density).toInt() }

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56))
            setBackgroundColor(bgColor)
            setPadding(dp(8), 0, dp(8), 0)
        }

        if (showBack) {
            val btnBack = ImageButton(activity).apply {
                setImageResource(android.R.drawable.ic_menu_revert)
                setBackgroundColor(Color.TRANSPARENT)
                imageTintList = ColorStateList.valueOf(txtColor)
                setOnClickListener { onBack?.invoke() }
            }
            header.addView(btnBack)
        }

        val titleTv = TextView(activity).apply {
            text     = title
            textSize = 20f
            setTextColor(txtColor)
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .also { it.marginStart = dp(8) }
        }
        header.addView(titleTv)

        extraView?.let { header.addView(it) }

        return header
    }
}
