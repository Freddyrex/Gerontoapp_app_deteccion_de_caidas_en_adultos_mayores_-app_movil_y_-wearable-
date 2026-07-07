package com.example.app_movile.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.example.app_movile.data.db.entities.SolicitudAyuda
import kotlinx.coroutines.flow.Flow

@Dao
interface SolicitudAyudaDao {
    @Query("SELECT * FROM solicitudes_ayuda ORDER BY timestamp DESC")
    fun getAll(): Flow<List<SolicitudAyuda>>

    @Insert
    suspend fun insert(solicitud: SolicitudAyuda): Long

    @Update
    suspend fun update(solicitud: SolicitudAyuda)

    @Query("UPDATE solicitudes_ayuda SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Int, status: String)

    @Query("SELECT * FROM solicitudes_ayuda WHERE codigoReloj = :codigo ORDER BY timestamp DESC")
    suspend fun getByCodigoReloj(codigo: String): List<SolicitudAyuda>

    @Query("SELECT * FROM solicitudes_ayuda WHERE codigoReloj = :codigo ORDER BY timestamp DESC")
    fun observeByCodigoReloj(codigo: String): Flow<List<SolicitudAyuda>>

    @Query("SELECT * FROM solicitudes_ayuda ORDER BY timestamp DESC")
    suspend fun getAllList(): List<SolicitudAyuda>

    @Query("DELETE FROM solicitudes_ayuda WHERE id = :id")
    suspend fun deleteById(id: Int)

    // Obtener solicitud por timestamp y deviceId para evitar duplicados
    @Query("SELECT * FROM solicitudes_ayuda WHERE timestamp = :timestamp AND codigoReloj = :deviceId")
    suspend fun obtenerPorTimestamp(timestamp: Long, deviceId: String): List<SolicitudAyuda>

    // Insert or Update helper
    suspend fun insertarOActualizar(solicitud: SolicitudAyuda) {
        val existente = getByCodigoReloj(solicitud.codigoReloj).find { it.timestamp == solicitud.timestamp }
        if (existente != null) {
            update(solicitud.copy(id = existente.id))
        } else {
            insert(solicitud)
        }
    }
}

