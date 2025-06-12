package com.example.locationsenderapp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface StationDataDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(data: StationDataEntity)

    @Query("SELECT * FROM station_data WHERE stationName = :station AND timestamp BETWEEN :start AND :end")
    suspend fun getDataForPeriod(station: String, start: Long, end: Long): List<StationDataEntity>

    @Query("SELECT DISTINCT stationName FROM station_data")
    suspend fun getStationNames(): List<String>
}