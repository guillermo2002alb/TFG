// AQIForegroundService.kt
package com.example.locationsenderapp.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.locationsenderapp.MainActivity
import kotlinx.coroutines.*

class AQIForegroundService : Service() {

    companion object {
        private const val TAG = "AQIForegroundService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "aqi_foreground_service"

        // Constantes para canal de notificaciones AQI (antes estaban en MainActivity)
        private const val AQI_NOTIFICATION_CHANNEL_ID = "aqi_notifications"
        private const val AQI_NOTIFICATION_ID = 1002

        const val ACTION_START_SERVICE = "START_AQI_SERVICE"
        const val ACTION_STOP_SERVICE = "STOP_AQI_SERVICE"

        fun startService(context: Context, frequency: Int) {
            val intent = Intent(context, AQIForegroundService::class.java).apply {
                action = ACTION_START_SERVICE
                putExtra("frequency", frequency)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopService(context: Context) {
            val intent = Intent(context, AQIForegroundService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            context.startService(intent)
        }
    }

    private var notificationFrequency = 30 // minutos por defecto
    private var serviceJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Servicio AQI creado")
        createNotificationChannel()
        createAQINotificationChannel() // Crear también el canal para notificaciones AQI
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SERVICE -> {
                notificationFrequency = intent.getIntExtra("frequency", 30)
                startForegroundService()
                startAQIMonitoring()
            }
            ACTION_STOP_SERVICE -> {
                stopForegroundService()
            }
        }
        return START_STICKY // El servicio se reinicia si es terminado por el sistema
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Servicio de Monitoreo AQI",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantiene el monitoreo de calidad del aire activo"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createAQINotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                AQI_NOTIFICATION_CHANNEL_ID,
                "Notificaciones AQI",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notificaciones sobre la calidad del aire"
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundService() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BreatheSafe Activo")
            .setContentText("Monitoreando calidad del aire cada $notificationFrequency min")
            .setSmallIcon(android.R.drawable.ic_dialog_info) // Cambia por tu icono
            .setContentIntent(pendingIntent)
            .setOngoing(true) // No se puede deslizar para cerrar
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        Log.d(TAG, "Servicio en primer plano iniciado")
    }

    private fun startAQIMonitoring() {
        serviceJob?.cancel()
        serviceJob = serviceScope.launch {
            while (isActive) {
                try {
                    checkAndSendAQINotification()
                    delay(notificationFrequency * 60 * 1000L) // Convertir minutos a ms
                } catch (e: Exception) {
                    Log.e(TAG, "Error en monitoreo AQI", e)
                    delay(60_000) // Reintentar en 1 minuto si hay error
                }
            }
        }
    }

    private suspend fun checkAndSendAQINotification() {
        withContext(Dispatchers.Main) {
            try {
                // Obtener datos del usuario interpolados
                val userSensor = MainActivity.sensorsData["user-location"]

                if (userSensor != null) {
                    val data = userSensor.pollutionData

                    // Calcular AQI como en la aplicación
                    val aqi = if (data.name.contains("Estimación")) {
                        val individualAqis = mutableListOf<Double>()

                        if (data.co > 0) individualAqis.add(data.co)
                        if (data.co2 > 0) individualAqis.add(data.co2)
                        if (data.so2 > 0) individualAqis.add(data.so2)
                        if (data.o3 > 0) individualAqis.add(data.o3)
                        if (data.no2 > 0) individualAqis.add(data.no2)
                        if (data.pm10 > 0) individualAqis.add(data.pm10)
                        if (data.pm4_0 > 0) individualAqis.add(data.pm4_0)
                        if (data.pm2_5 > 0) individualAqis.add(data.pm2_5)
                        if (data.pm1_0 > 0) individualAqis.add(data.pm1_0)

                        individualAqis.maxOrNull()?.toInt() ?: data.aqi.toInt()
                    } else {
                        data.aqi.toInt()
                    }

                    val aqiLevel = when {
                        aqi <= 33 -> "Muy Buena"
                        aqi <= 66 -> "Buena"
                        aqi <= 100 -> "Regular"
                        aqi <= 200 -> "Mala"
                        else -> "Muy Mala"
                    }

                    sendAQINotification(aqi, aqiLevel)
                    Log.d(TAG, "Notificación AQI enviada: $aqi ($aqiLevel)")
                } else {
                    Log.w(TAG, "No hay datos de usuario disponibles para notificación")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error enviando notificación AQI", e)
            }
        }
    }

    private fun sendAQINotification(aqi: Int, aqiLevel: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationText = "Calidad del aire: $aqiLevel (AQI: $aqi)"
        val icon = when {
            aqi <= 33 -> "😊"
            aqi <= 66 -> "🙂"
            aqi <= 100 -> "😐"
            aqi <= 200 -> "😷"
            else -> "⚠️"
        }

        val builder = NotificationCompat.Builder(this, AQI_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("$icon BreatheSafe")
            .setContentText(notificationText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notificationText))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)

        try {
            with(NotificationManagerCompat.from(this)) {
                notify(AQI_NOTIFICATION_ID, builder.build())
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "No se pudieron enviar notificaciones: falta permiso", e)
        }
    }

    private fun stopForegroundService() {
        Log.d(TAG, "Deteniendo servicio AQI")
        serviceJob?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob?.cancel()
        serviceScope.cancel()
        Log.d(TAG, "Servicio AQI destruido")
    }
}