package com.example.locationsenderapp.data

import com.example.locationsenderapp.SensorInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class StationRepository(private val dao: StationDataDao) {

    suspend fun insert(entity: StationDataEntity) = withContext(Dispatchers.IO) {
        dao.insert(entity)
    }

    suspend fun getStationNames(): List<String> = withContext(Dispatchers.IO) {
        dao.getStationNames()
    }

    suspend fun getAverageStats(station: String, start: Long, end: Long): StationStats = withContext(Dispatchers.IO) {
        val data = dao.getDataForPeriod(station, start, end)
        if (data.isEmpty()) return@withContext StationStats()
        StationStats(
            pm25 = data.map { it.pm25 }.average(),
            pm10 = data.map { it.pm10 }.average(),
            no2 = data.map { it.no2 }.average(),
            so2 = data.map { it.so2 }.average(),
            o3 = data.map { it.o3 }.average(),
            co = data.map { it.co }.average(),
            temperature = data.map { it.temperature }.average(),
            humidity = data.map { it.humidity }.average()
        )
    }

    companion object {
        fun fromSensorInfo(sensor: SensorInfo): StationDataEntity {
            val p = sensor.pollutionData
            return StationDataEntity(
                stationName = p.name,
                timestamp = System.currentTimeMillis(),
                pm25 = p.pm2_5,
                pm10 = p.pm10,
                no2 = p.no2,
                so2 = p.so2,
                o3 = p.o3,
                co = p.co,
                temperature = p.temperature,
                humidity = p.humidity
            )
        }
    }
}

data class StationStats(
    val pm25: Double = 0.0,
    val pm10: Double = 0.0,
    val no2: Double = 0.0,
    val so2: Double = 0.0,
    val o3: Double = 0.0,
    val co: Double = 0.0,
    val temperature: Double = 0.0,
    val humidity: Double = 0.0
)