package com.example.app_movile.ui.main

import android.content.res.ColorStateList
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.app_movile.R

object BottomBarHelper {

    fun createBottomBarContainer(activity: AppCompatActivity): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            val p = dp(activity, 8)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(p, p / 2, p, p / 2)
            setBackgroundResource(R.drawable.bg_bottom_bar)
        }
    }

    fun createBottomBarButton(
        activity: AppCompatActivity,
        iconRes: Int,
        contentDescription: String,
        onClick: () -> Unit
    ): ImageButton {
        return ImageButton(activity).apply {
            setImageResource(iconRes)
            background = null
            imageTintList = ContextCompat.getColorStateList(activity, R.color.bottom_icon_tint)
            this.contentDescription = contentDescription
            val padding = dp(activity, 8)
            setPadding(padding, padding / 2, padding, padding / 2)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginStart = dp(activity, 12); it.marginEnd = dp(activity, 12) }
            setOnClickListener { onClick() }
        }
    }

    private fun dp(activity: AppCompatActivity, value: Int): Int {
        return (value * activity.resources.displayMetrics.density).toInt()
    }
}
