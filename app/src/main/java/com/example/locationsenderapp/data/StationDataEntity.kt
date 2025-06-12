package com.example.locationsenderapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "station_data")
data class StationDataEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val stationName: String,
    val timestamp: Long,
    val pm25: Double,
    val pm10: Double,
    val no2: Double,
    val so2: Double,
    val o3: Double,
    val co: Double,
    val temperature: Double,
    val humidity: Double
)