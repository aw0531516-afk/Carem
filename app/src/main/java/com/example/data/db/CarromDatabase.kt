package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ShotRecordEntity::class, DiagnosticLogEntity::class],
    version = 1,
    exportSchema = false
)
abstract class CarromDatabase : RoomDatabase() {
    abstract fun shotDao(): ShotDao

    companion object {
        @Volatile
        private var INSTANCE: CarromDatabase? = null

        fun getInstance(context: Context): CarromDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    CarromDatabase::class.java,
                    "carrom_pilot_training.db"
                )
                    // Never destroy user training records on upgrade; future versions add explicit Migrations.
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
