package com.example.app_movile.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.example.app_movile.data.db.entities.EventoCaida
import kotlinx.coroutines.flow.Flow

@Dao
interface EventoCaidaDao {
    @Query("SELECT * FROM eventos_caida ORDER BY timestamp DESC")
    fun getAll(): Flow<List<EventoCaida>>

    @Insert
    suspend fun insert(evento: EventoCaida): Long

    @Update
    suspend fun update(evento: EventoCaida)

    @Query("UPDATE eventos_caida SET estadoRespuesta = :estado WHERE id = :id")
    suspend fun updateEstado(id: Int, estado: String)

    // Obtener un evento por su id (útil para comprobaciones diferidas)
    @Query("SELECT * FROM eventos_caida WHERE id = :id LIMIT 1")
    suspend fun getById(id: Int): EventoCaida?

    // Obtener eventos almacenados localmente por código de reloj
    @Query("SELECT * FROM eventos_caida WHERE codigoReloj = :codigo ORDER BY timestamp DESC")
    suspend fun getByCodigoReloj(codigo: String): List<EventoCaida>

    @Query("DELETE FROM eventos_caida WHERE id = :id")
    suspend fun deleteById(id: Int)

    // Obtener evento por timestamp y deviceId para evitar duplicados
    @Query("SELECT * FROM eventos_caida WHERE timestamp = :timestamp AND codigoReloj = :deviceId")
    suspend fun obtenerPorTimestamp(timestamp: Long, deviceId: String): List<EventoCaida>

    // Insert or Update helper
    suspend fun insertarOActualizar(evento: EventoCaida) {
        val existente = getById(evento.id)
        if (existente != null) {
            update(evento)
        } else {
            insert(evento)
        }
    }
}

