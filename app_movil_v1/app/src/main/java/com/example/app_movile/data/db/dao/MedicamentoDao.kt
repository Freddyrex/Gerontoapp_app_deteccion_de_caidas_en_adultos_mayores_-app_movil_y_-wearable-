package com.example.app_movile.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.app_movile.data.db.entities.Medicamento
import kotlinx.coroutines.flow.Flow

@Dao
interface MedicamentoDao {
    @Query("SELECT * FROM medicamentos WHERE activo = 1 ORDER BY nombre ASC")
    fun getAllActivos(): Flow<List<Medicamento>>

    @Query("SELECT * FROM medicamentos ORDER BY nombre ASC")
    fun getAll(): Flow<List<Medicamento>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(medicamento: Medicamento): Long

    @Update
    suspend fun update(medicamento: Medicamento)

    @Delete
    suspend fun delete(medicamento: Medicamento)

    @Query("UPDATE medicamentos SET activo = :activo WHERE id = :id")
    suspend fun setActivo(id: Int, activo: Boolean)

    @Query("SELECT * FROM medicamentos WHERE id = :id LIMIT 1")
    suspend fun getById(id: Int): Medicamento?

    /** Lista completa síncrona (no Flow) para uso en procesos de sincronización */
    @Query("SELECT * FROM medicamentos ORDER BY nombre ASC")
    suspend fun getAllSync(): List<Medicamento>

    @Query("SELECT * FROM medicamentos WHERE serverId = :serverId LIMIT 1")
    suspend fun getByServerId(serverId: String): Medicamento?

    /** Inserción masiva con reemplazo en conflicto — para re-importar desde Firebase */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(medicamentos: List<Medicamento>)

    /** Limpia toda la tabla — usar antes de re-importar la lista completa */
    @Query("DELETE FROM medicamentos")
    suspend fun deleteAll()
}
