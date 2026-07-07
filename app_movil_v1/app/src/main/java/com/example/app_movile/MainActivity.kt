package com.example.app_movile

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Actividad de compatibilidad; redirige al entrypoint real en ui.main.MainActivity.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, com.example.app_movile.ui.main.MainActivity::class.java))
        finish()
    }
}