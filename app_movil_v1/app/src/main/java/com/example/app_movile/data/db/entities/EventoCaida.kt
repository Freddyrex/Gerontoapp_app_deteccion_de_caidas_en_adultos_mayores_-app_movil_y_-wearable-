package com.example.app_movile.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "eventos_caida")
data class EventoCaida(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val timestamp: Long,
    val latitud: Double,
    val longitud: Double,
    val medicamentosActivos: String,
    // Código de 3 dígitos del reloj que originó el evento
    val codigoReloj: String = "",
    /**
     * Severidad del evento de detección de movimiento / caída:
     *   "movimiento_brusco"  → sacudida fuerte sin caída confirmada
     *   "caida_leve"         → caída de bajo impacto
     *   "caida_grave"        → caída de alto impacto
     * (el campo acepta también los valores legacy "media" / "leve" / "grave")
     */
    val severidad: String = "caida_leve",
    // Estado de respuesta: PENDIENTE | NOTIFICADA | ATENDIDA | IGNORADA
    val estadoRespuesta: String = "PENDIENTE"
)
