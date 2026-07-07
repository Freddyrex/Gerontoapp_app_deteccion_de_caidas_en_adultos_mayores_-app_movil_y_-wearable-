package com.example.app_movile.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.app_movile.data.db.converters.Converters
import com.example.app_movile.data.db.dao.EventoCaidaDao
import com.example.app_movile.data.db.dao.MedicamentoDao
import com.example.app_movile.data.db.dao.SolicitudAyudaDao
import com.example.app_movile.data.db.entities.EventoCaida
import com.example.app_movile.data.db.entities.Medicamento
import com.example.app_movile.data.db.entities.SolicitudAyuda

/**
 * AppDatabase — alias de SiagDatabase para compatibilidad con código existente.
 * Toda la lógica real de migraciones vive en SiagDatabase; aquí sólo se
 * expone el mismo singleton bajo el nombre AppDatabase.
 */
@Database(
    entities = [Medicamento::class, EventoCaida::class, SolicitudAyuda::class],
    version = 7,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun medicamentoDao(): MedicamentoDao
    abstract fun eventoCaidaDao(): EventoCaidaDao
    abstract fun solicitudAyudaDao(): SolicitudAyudaDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE medicamentos ADD COLUMN notas TEXT NOT NULL DEFAULT ''")
            }
        }
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try { db.execSQL("ALTER TABLE medicamentos ADD COLUMN horaReferencia TEXT") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE medicamentos ADD COLUMN vigenciaDesde TEXT") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE medicamentos ADD COLUMN vigenciaHasta TEXT") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE medicamentos ADD COLUMN siempreAvisar INTEGER NOT NULL DEFAULT 1") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE eventos_caida ADD COLUMN codigoReloj TEXT NOT NULL DEFAULT ''") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE eventos_caida ADD COLUMN severidad TEXT NOT NULL DEFAULT 'caida_leve'") } catch (_: Exception) {}
            }
        }
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try { db.execSQL("ALTER TABLE eventos_caida ADD COLUMN codigoReloj TEXT NOT NULL DEFAULT ''") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE eventos_caida ADD COLUMN severidad TEXT NOT NULL DEFAULT 'caida_leve'") } catch (_: Exception) {}
            }
        }
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try { db.execSQL("ALTER TABLE medicamentos ADD COLUMN intervalHours INTEGER NOT NULL DEFAULT 8") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE medicamentos ADD COLUMN updatedAt_ms INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) {}
                db.execSQL("""
                    UPDATE medicamentos SET intervalHours = CASE
                        WHEN frecuencia LIKE '4%'  THEN 4
                        WHEN frecuencia LIKE '8%'  THEN 8
                        WHEN frecuencia LIKE '12%' THEN 12
                        WHEN frecuencia LIKE '24%' THEN 24
                        ELSE 8
                    END
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS solicitudes_ayuda (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        latitud REAL NOT NULL,
                        longitud REAL NOT NULL,
                        mensaje TEXT NOT NULL DEFAULT 'Necesito asistencia',
                        codigoReloj TEXT NOT NULL DEFAULT '',
                        status TEXT NOT NULL DEFAULT 'pendiente'
                    )
                """.trimIndent())
            }
        }
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try { db.execSQL("ALTER TABLE medicamentos ADD COLUMN serverId TEXT") } catch (_: Exception) {}
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "siag_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_6_7)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
