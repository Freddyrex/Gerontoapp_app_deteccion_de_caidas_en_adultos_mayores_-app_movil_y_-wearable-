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

@Database(
    entities = [Medicamento::class, EventoCaida::class, SolicitudAyuda::class],
    version = 5,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class SiagDatabase : RoomDatabase() {

    abstract fun medicamentoDao(): MedicamentoDao
    abstract fun eventoCaidaDao(): EventoCaidaDao
    abstract fun solicitudAyudaDao(): SolicitudAyudaDao

    companion object {
        @Volatile
        private var INSTANCE: SiagDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE medicamentos ADD COLUMN notas TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE eventos_caida ADD COLUMN codigoReloj TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE eventos_caida ADD COLUMN severidad TEXT NOT NULL DEFAULT 'caida_leve'")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Columnas ya pueden existir en instalaciones previas; ignorar si ya existen
                try { db.execSQL("ALTER TABLE eventos_caida ADD COLUMN codigoReloj TEXT NOT NULL DEFAULT ''") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE eventos_caida ADD COLUMN severidad TEXT NOT NULL DEFAULT 'caida_leve'") } catch (_: Exception) {}
            }
        }

        /**
         * v4 → v5:
         *  - medicamentos: agrega intervalHours, updatedAt_ms, renames frecuencia safe
         *  - eventos_caida: estandariza columna severidad (ya existente, solo default cambia)
         *  - nueva tabla solicitudes_ayuda
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // medicamentos: nuevas columnas
                try { db.execSQL("ALTER TABLE medicamentos ADD COLUMN intervalHours INTEGER NOT NULL DEFAULT 8") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE medicamentos ADD COLUMN updatedAt_ms INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) {}
                // Rellenar intervalHours desde frecuencia textual legacy
                db.execSQL("""
                    UPDATE medicamentos SET intervalHours = CASE
                        WHEN frecuencia LIKE '4%'  THEN 4
                        WHEN frecuencia LIKE '8%'  THEN 8
                        WHEN frecuencia LIKE '12%' THEN 12
                        WHEN frecuencia LIKE '24%' THEN 24
                        ELSE 8
                    END
                """.trimIndent())
                // solicitudes_ayuda: nueva tabla
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

        fun getInstance(context: Context): SiagDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SiagDatabase::class.java,
                    "siag_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
