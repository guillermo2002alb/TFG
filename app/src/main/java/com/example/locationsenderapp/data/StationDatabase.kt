package com.example.locationsenderapp.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [StationDataEntity::class], version = 1)
abstract class StationDatabase : RoomDatabase() {
    abstract fun stationDataDao(): StationDataDao

    companion object {
        @Volatile
        private var INSTANCE: StationDatabase? = null

        fun getDatabase(context: Context): StationDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    StationDatabase::class.java,
                    "station_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}