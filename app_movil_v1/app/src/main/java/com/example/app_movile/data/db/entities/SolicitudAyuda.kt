package com.example.app_movile.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "solicitudes_ayuda")
data class SolicitudAyuda(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val timestamp: Long,
    val latitud: Double,
    val longitud: Double,
    val mensaje: String = "Necesito asistencia",
    val codigoReloj: String = "",
    val status: String = "pendiente"   // pendiente | atendida | ignorada
)
