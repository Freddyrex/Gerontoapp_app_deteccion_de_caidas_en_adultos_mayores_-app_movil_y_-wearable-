package com.example.app_movile.util

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class PermissionManager(private val activity: AppCompatActivity) {

    private val locationLauncher: ActivityResultLauncher<String> =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) verificarUbicacionSegundoPlano() else mostrarDialogoPermisoRechazado()
        }

    fun iniciarFlujoPermisos() {
        when {
            ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED -> {
                mostrarDialogoEducativo(
                    titulo = "Permiso de ubicacion requerido",
                    mensaje = "Esta app necesita tu ubicacion para mostrar donde ocurrio la caida.",
                    onAceptar = { locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
                )
            }
            else -> verificarUbicacionSegundoPlano()
        }
    }

    private fun verificarUbicacionSegundoPlano() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (ContextCompat.checkSelfPermission(
                    activity,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                mostrarDialogoEducativo(
                    titulo = "Permiso de ubicacion permanente",
                    mensaje = "En la siguiente pantalla selecciona 'Permitir todo el tiempo'.",
                    onAceptar = { abrirConfiguracionPermisos() }
                )
            }
        }
    }

    private fun abrirConfiguracionPermisos() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", activity.packageName, null)
        )
        activity.startActivity(intent)
    }

    private fun mostrarDialogoEducativo(titulo: String, mensaje: String, onAceptar: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle(titulo)
            .setMessage(mensaje)
            .setPositiveButton("Continuar") { _, _ -> onAceptar() }
            .setNegativeButton("Ahora no", null)
            .setCancelable(false)
            .show()
    }

    private fun mostrarDialogoPermisoRechazado() {
        AlertDialog.Builder(activity)
            .setTitle("Permiso necesario")
            .setMessage("Sin permiso de ubicacion no se podra mostrar la caida en el mapa.")
            .setPositiveButton("Ir a configuracion") { _, _ -> abrirConfiguracionPermisos() }
            .setNegativeButton("Entendido", null)
            .show()
    }
}

