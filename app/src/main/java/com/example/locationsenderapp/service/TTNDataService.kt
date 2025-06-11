package com.example.locationsenderapp.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.example.locationsenderapp.MainActivity
import com.example.locationsenderapp.TTNSensorData
import kotlinx.coroutines.*
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.util.*
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import org.eclipse.paho.client.mqttv3.MqttConnectOptions

class TTNDataService : Service() {

    companion object {
        private const val TAG = "TTNDataService"

        // TTN Configuration
        private const val TTN_APP_ID = "my-sensor-allsense"
        private const val TTN_API_KEY = "NNSXS.ORWT4GOE5OGK7Z2R3375HKA6FXXDJV753SAIZ7Q.XL3PFKRWSHXF6Z4G3LZARPUGHPHZOO7AUEADDOJJNYEFUD6DB72A"
        private const val TTN_BROKER = "eu1.cloud.thethings.network"
        private const val TTN_PORT = 8883

        // Topic para recibir todos los uplinks de tu aplicación TTN
        private const val TTN_TOPIC = "v3/$TTN_APP_ID@ttn/devices/+/up"
    }

    private var mqttClient: MqttClient? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Servicio TTN creado")
        connectToTTN()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Servicio TTN iniciado")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Servicio TTN destruido")
        disconnectFromTTN()
        coroutineScope.cancel()
    }

    private fun connectToTTN() {
        coroutineScope.launch {
            try {
                val clientId = "android_" + UUID.randomUUID().toString()
                mqttClient = MqttClient(
                    "ssl://$TTN_BROKER:$TTN_PORT",
                    clientId,
                    MemoryPersistence()
                )

                val options = MqttConnectOptions().apply {
                    isAutomaticReconnect = true
                    isCleanSession = true
                    userName = "$TTN_APP_ID@ttn"
                    password = TTN_API_KEY.toCharArray()
                    connectionTimeout = 30
                    keepAliveInterval = 60

                    // Configurar SSL/TLS
                    socketFactory = getSocketFactory()
                }

                mqttClient?.setCallback(object : MqttCallback {
                    override fun connectionLost(cause: Throwable?) {
                        Log.e(TAG, "Conexión perdida: ${cause?.message}")
                        // Intentar reconectar
                        reconnectToTTN()
                    }

                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        Log.d(TAG, "Mensaje recibido en topic: $topic")
                        message?.let {
                            processMessage(String(it.payload))
                        }
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {
                        // No usado para suscripción
                    }
                })

                Log.d(TAG, "Conectando a TTN...")
                mqttClient?.connect(options)

                Log.d(TAG, "Conectado exitosamente. Suscribiendo a $TTN_TOPIC")
                mqttClient?.subscribe(TTN_TOPIC, 1)

            } catch (e: Exception) {
                Log.e(TAG, "Error conectando a TTN", e)
                // Reintentar en 30 segundos
                delay(30000)
                connectToTTN()
            }
        }
    }

    private fun reconnectToTTN() {
        coroutineScope.launch {
            delay(5000) // Esperar 5 segundos antes de reconectar
            if (mqttClient?.isConnected == false) {
                connectToTTN()
            }
        }
    }

    private fun disconnectFromTTN() {
        try {
            mqttClient?.disconnect()
            mqttClient?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error desconectando de TTN", e)
        }
    }

    private fun processMessage(payload: String) {
        try {
            Log.d(TAG, "Procesando payload: $payload")

            val json = JSONObject(payload)
            val deviceId = json.getJSONObject("end_device_ids").getString("device_id")

            // Obtener el payload decodificado
            val uplinkMessage = json.getJSONObject("uplink_message")
            val decodedPayload = uplinkMessage.optJSONObject("decoded_payload")

            if (decodedPayload == null) {
                Log.e(TAG, "No hay decoded_payload en el mensaje")
                return
            }

            // Log para debug
            Log.d(TAG, "Decoded payload: $decodedPayload")

            // El payload puede estar anidado en 'data'
            val data = if (decodedPayload.has("data")) {
                decodedPayload.getJSONObject("data")
            } else {
                decodedPayload
            }

            // Log para ver qué campos vienen en el payload
            val keys = mutableListOf<String>()
            val keysIterator = data.keys()
            while (keysIterator.hasNext()) {
                keys.add(keysIterator.next())
            }
            Log.d(TAG, "Campos disponibles en data: $keys")

            // Extraer ubicación
            var latitude: Double? = null
            var longitude: Double? = null

            // Intentar obtener ubicación del gateway
            val locations = uplinkMessage.optJSONArray("rx_metadata")
            if (locations != null && locations.length() > 0) {
                val firstLocation = locations.getJSONObject(0)
                if (firstLocation.has("location")) {
                    val location = firstLocation.getJSONObject("location")
                    latitude = location.optDouble("latitude", 0.0)
                    longitude = location.optDouble("longitude", 0.0)
                }
            }

            // Si no hay ubicación del gateway, buscar en el payload
            if (latitude == null || longitude == null || latitude == 0.0 || longitude == 0.0) {
                // Intentar diferentes nombres de campos para la ubicación
                latitude = data.optDouble("latitude",
                    data.optDouble("lat",
                        data.optDouble("gps_lat", 37.1773))) // Default Granada

                longitude = data.optDouble("longitude",
                    data.optDouble("lon",
                        data.optDouble("lng",
                            data.optDouble("gps_lon", -3.6082))))
            }

            // Extraer datos de contaminación - función helper para obtener valores numéricos
            fun getNumericValue(data: JSONObject, vararg keys: String): Double {
                for (key in keys) {
                    if (data.has(key)) {
                        return when (val value = data.get(key)) {
                            is Double -> value
                            is Int -> value.toDouble()
                            is Long -> value.toDouble()
                            is String -> value.toDoubleOrNull() ?: 0.0
                            else -> 0.0
                        }
                    }
                }
                return 0.0
            }

            // Extraer datos usando la función helper
            val pm1_0 = getNumericValue(data, "pm1_0", "pm1", "PM1")
            val pm2_5 = getNumericValue(data, "pm2_5", "pm25", "PM2_5", "PM25")
            val pm4_0 = getNumericValue(data, "pm4_0", "pm4", "PM4")
            val pm10 = getNumericValue(data, "pm10", "pm10_0", "pm_10", "PM_10")
            val temperature = getNumericValue(data, "temperature", "temp", "t")
            val humidity = getNumericValue(data, "humidity", "hum", "h", "rh")
            val vocIndex = getNumericValue(data, "voc_index", "voc", "VOC", "vocIndex", "VOC_index")
            val noxIndex = getNumericValue(data, "nox_index", "nox", "NOx", "noxIndex", "NOx_index")
            val co = getNumericValue(data, "co", "CO")
            val co2 = getNumericValue(data, "co2", "CO2")
            val no2 = getNumericValue(data, "no2", "NO2")

            // Log detallado de cada campo
            Log.d(TAG, "=== VALORES EXTRAÍDOS ===")
            Log.d(TAG, "temperature: $temperature")
            Log.d(TAG, "humidity: $humidity")
            Log.d(TAG, "pm1_0: $pm1_0")
            Log.d(TAG, "pm2_5: $pm2_5")
            Log.d(TAG, "pm4_0: $pm4_0")
            Log.d(TAG, "pm10: $pm10")
            Log.d(TAG, "vocIndex: $vocIndex")
            Log.d(TAG, "noxIndex: $noxIndex")
            Log.d(TAG, "======================")

            val timestamp = uplinkMessage.getString("received_at")

            Log.d(TAG, """
                Datos extraídos de $deviceId:
                Ubicación: $latitude, $longitude
                PM1.0: $pm1_0
                PM2.5: $pm2_5
                PM4.0: $pm4_0
                PM10: $pm10
                Temperatura: $temperature°C
                Humedad: $humidity%
                VOC: $vocIndex
                NOx: $noxIndex
                Campos en data: $keys
            """.trimIndent())

            // Enviar datos actualizados
            updateMainActivity(
                deviceId = deviceId,
                latitude = latitude,
                longitude = longitude,
                pm1_0 = pm1_0,
                pm2_5 = pm2_5,
                pm4_0 = pm4_0,
                pm10 = pm10,
                temperature = temperature,
                humidity = humidity,
                vocIndex = vocIndex,
                noxIndex = noxIndex,
                timestamp = timestamp,
                co = co,
                co2 = co2,
                no2 = no2
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error procesando mensaje", e)
        }
    }

    private fun updateMainActivity(
        deviceId: String,
        latitude: Double,
        longitude: Double,
        pm1_0: Double,
        pm2_5: Double,
        pm4_0: Double,
        pm10: Double,
        temperature: Double,
        humidity: Double,
        vocIndex: Double,
        noxIndex: Double,
        co: Double,
        co2: Double,
        no2: Double,
        timestamp: String
    ) {
        // Actualizar el singleton en MainActivity con los nuevos datos
        val ttnData = TTNSensorData(
            deviceId = deviceId,
            latitude = latitude,
            longitude = longitude,
            pm1_0 = pm1_0,
            pm2_5 = pm2_5,
            pm4_0 = pm4_0,
            pm10 = pm10,
            temperature = temperature,
            humidity = humidity,
            vocIndex = vocIndex,
            noxIndex = noxIndex,
            timestamp = System.currentTimeMillis(),
            co = co,
            co2 = co2,
            no2 = no2,
        )

        // Actualizar el singleton
        MainActivity.currentTTNData = ttnData

        Log.d(TAG, "Datos TTN actualizados en MainActivity: $ttnData")
    }

    private fun getSocketFactory(): SSLSocketFactory {
        return SSLContext.getDefault().socketFactory
    }
}