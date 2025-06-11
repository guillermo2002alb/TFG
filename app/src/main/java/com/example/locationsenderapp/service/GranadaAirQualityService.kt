package com.example.locationsenderapp.service

import android.util.Log
import com.example.locationsenderapp.MainActivity
import com.example.locationsenderapp.PollutionData
import com.example.locationsenderapp.SensorInfo
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.json.JSONArray
import java.util.*
import java.util.concurrent.TimeUnit

class GranadaAirQualityService {

    companion object {
        private const val TAG = "GranadaAirService"

        // API Key de WAQI
        private const val WAQI_API_KEY = "634327ad77e0263f19174f2a4ce7663e4a562d57"

        // Base URL de la API WAQI
        private const val WAQI_API_BASE_URL = "https://api.waqi.info"

        // Endpoints
        private const val SEARCH_ENDPOINT = "$WAQI_API_BASE_URL/v2/search"
        private const val FEED_ENDPOINT = "$WAQI_API_BASE_URL/feed"

        // Ubicaciones de las estaciones (actualizadas según los datos encontrados)
        private val STATION_LOCATIONS = mapOf(
            "Granada Norte" to LatLng(37.196375, -3.612113),
            "Palacio de Congresos" to LatLng(37.165944, -3.598586)
        )

        // UIDs de las estaciones (los obtendremos dinámicamente)
        private var stationUIDs = mutableMapOf<String, String>()
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun startPolling() {
        Log.d(TAG, "Iniciando servicio de datos de WAQI Granada")
        coroutineScope.launch {
            // Buscar estaciones al inicio
            searchGranadaStations()

            // Actualizar cada 5 minutos
            while (isActive) {
                try {
                    fetchWAQIData()
                } catch (e: Exception) {
                    Log.e(TAG, "Error obteniendo datos de WAQI API", e)
                }
                delay(300000) // 5 minutos
            }
        }
    }

    private suspend fun searchGranadaStations() {
        withContext(Dispatchers.IO) {
            try {
                val url = "$SEARCH_ENDPOINT/?token=$WAQI_API_KEY&keyword=Granada"
                Log.d(TAG, "Buscando estaciones en Granada...")

                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .build()

                val response = httpClient.newCall(request).execute()

                if (response.isSuccessful) {
                    val jsonResponse = response.body?.string()
                    if (!jsonResponse.isNullOrEmpty()) {
                        val json = JSONObject(jsonResponse)

                        if (json.getString("status") == "ok") {
                            val data = json.getJSONArray("data")
                            Log.d(TAG, "✅ Estaciones encontradas: ${data.length()}")

                            // Procesar cada estación encontrada
                            for (i in 0 until data.length()) {
                                val station = data.getJSONObject(i)
                                val stationName = station.getJSONObject("station").getString("name")
                                val uid = station.getString("uid")

                                Log.d(TAG, "Estación encontrada: $stationName (UID: $uid)")

                                // Mapear los nombres encontrados a nuestras estaciones
                                when {
                                    stationName.contains("norte", ignoreCase = true) -> {
                                        stationUIDs["Granada Norte"] = uid
                                    }
                                    stationName.contains("Congresos", ignoreCase = true) ||
                                            stationName.contains("Palacio", ignoreCase = true) -> {
                                        stationUIDs["Palacio de Congresos"] = uid
                                    }
                                }
                            }

                            // Ahora obtener datos de las estaciones encontradas
                            fetchWAQIData()
                        }
                    }
                } else {
                    Log.e(TAG, "Error HTTP: ${response.code} - ${response.message}")
                }

                response.close()

            } catch (e: Exception) {
                Log.e(TAG, "Error buscando estaciones", e)
            }
        }
    }

    private suspend fun fetchWAQIData() {
        withContext(Dispatchers.IO) {
            try {
                // Obtener datos para cada estación conocida
                stationUIDs.forEach { (name, uid) ->
                    fetchStationData(name, uid)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error en fetchWAQIData", e)
            }
        }
    }

    private fun fetchStationData(stationName: String, uid: String) {
        try {
            val url = "$FEED_ENDPOINT/@$uid/?token=$WAQI_API_KEY"
            Log.d(TAG, "Obteniendo datos de $stationName (UID: $uid)")

            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()

            val response = httpClient.newCall(request).execute()

            if (response.isSuccessful) {
                val jsonResponse = response.body?.string()
                if (!jsonResponse.isNullOrEmpty()) {
                    val json = JSONObject(jsonResponse)

                    if (json.getString("status") == "ok") {
                        val data = json.getJSONObject("data")
                        Log.d(TAG, "✅ Datos obtenidos para $stationName")

                        updateStationWithWAQIData(stationName, data)
                    } else {
                        Log.e(TAG, "Error en respuesta API: ${json.getString("status")}")
                    }
                }
            } else {
                Log.e(TAG, "Error HTTP: ${response.code} - ${response.message}")
            }

            response.close()

        } catch (e: Exception) {
            Log.e(TAG, "Error obteniendo datos de $stationName", e)
        }
    }

    private fun updateStationWithWAQIData(stationName: String, data: JSONObject) {
        try {
            // Extraer AQI
            val aqi = data.optInt("aqi", 0)

            // Extraer información de la ciudad
            val city = data.getJSONObject("city")

            // Obtener coordenadas si están disponibles
            val geo = city.optJSONArray("geo")
            val location = if (geo != null && geo.length() >= 2) {
                LatLng(geo.getDouble(0), geo.getDouble(1))
            } else {
                STATION_LOCATIONS[stationName] ?: return
            }

            // Extraer datos de contaminantes
            val iaqi = data.optJSONObject("iaqi")
            var pm25 = 0.0
            var pm10 = 0.0
            var no2 = 0.0
            var so2 = 0.0
            var o3 = 0.0
            var co = 0.0
            var temp = 0.0
            var humidity = 0.0

            if (iaqi != null) {
                pm25 = iaqi.optJSONObject("pm25")?.optDouble("v", 0.0) ?: 0.0
                pm10 = iaqi.optJSONObject("pm10")?.optDouble("v", 0.0) ?: 0.0
                no2 = iaqi.optJSONObject("no2")?.optDouble("v", 0.0) ?: 0.0
                so2 = iaqi.optJSONObject("so2")?.optDouble("v", 0.0) ?: 0.0
                o3 = iaqi.optJSONObject("o3")?.optDouble("v", 0.0) ?: 0.0
                co = iaqi.optJSONObject("co")?.optDouble("v", 0.0) ?: 0.0
                temp = iaqi.optJSONObject("t")?.optDouble("v", 0.0) ?: 0.0
                humidity = iaqi.optJSONObject("h")?.optDouble("v", 0.0) ?: 0.0
            }

            Log.d(TAG, "Actualizando $stationName con datos WAQI:")
            Log.d(TAG, "  AQI: $aqi")
            Log.d(TAG, "  PM2.5: $pm25 ")
            Log.d(TAG, "  PM10: $pm10 ")
            Log.d(TAG, "  NO2: $no2 ")
            Log.d(TAG, "  SO2: $so2 ")
            Log.d(TAG, "  O3: $o3 ")
            Log.d(TAG, "  Temp: $temp°C")
            Log.d(TAG, "  Humedad: $humidity%")

            val sensorInfo = SensorInfo(
                deviceId = "granada-$stationName",
                location = location,
                pollutionData = PollutionData(
                    name = stationName,
                    pm1_0 = 0.0, // Ya no usamos para AQI
                    pm2_5 = pm25,
                    pm4_0 = 0.0, // Ya no usamos para CO
                    pm10 = pm10,
                    o3 = o3, // Ozono en su propio campo
                    co = co, // CO en su propio campo
                    aqi = aqi.toDouble(), // AQI en su propio campo
                    temperature = temp,
                    humidity = humidity,
                    vocIndex = 0.0, // SO2
                    noxIndex = 0.0,
                    co2 = 0.0,
                    no2 = no2,
                    so2 = so2
                ),
                lastUpdate = System.currentTimeMillis()
            )

            MainActivity.sensorsData[sensorInfo.deviceId] = sensorInfo

        } catch (e: Exception) {
            Log.e(TAG, "Error procesando datos WAQI para $stationName", e)
        }
    }

}