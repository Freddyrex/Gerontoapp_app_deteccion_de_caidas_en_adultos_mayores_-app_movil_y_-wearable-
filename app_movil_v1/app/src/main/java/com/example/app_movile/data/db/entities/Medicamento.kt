package com.example.app_movile.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "medicamentos")
data class Medicamento(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    // Identificador estable en el servidor (clave RTDB push key o UUID). Nullable para registros locales aun no sincronizados.
    val serverId: String? = null,
    val nombre: String,
    val dosis: String,
    // Intervalo en horas: 4, 8, 12 o 24  (reemplaza la cadena "frecuencia" antigua)
    val intervalHours: Int = 8,
    // Hora de referencia en formato "HH:mm" (ej. "08:00")
    val horaReferencia: String? = null,
    // Vigencia: fechas "yyyy-MM-dd" o null cuando siempreAvisar=true
    val vigenciaDesde: String? = null,
    val vigenciaHasta: String? = null,
    // Si true, siempre avisar (ignora vigencias)
    val siempreAvisar: Boolean = true,
    val esAltoRiesgo: Boolean = false,
    val notas: String = "",
    val activo: Boolean = true,
    // Columna de compatibilidad: se calcula desde intervalHours y horaReferencia
    val frecuencia: String = "${intervalHours}h",
    val horarios: List<String> = emptyList(),
    // Fecha de última actualización (ms UTC)
    val updatedAt_ms: Long = System.currentTimeMillis()
)
