// AQIAlarmReceiver.kt - Sistema de respaldo con AlarmManager
package com.example.locationsenderapp.service

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.locationsenderapp.MainActivity
import kotlinx.coroutines.*

class AQIAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AQIAlarmReceiver"
        private const val ACTION_AQI_CHECK = "com.example.locationsenderapp.AQI_CHECK"
        private const val EXTRA_FREQUENCY = "frequency"

        // Constantes propias para notificaciones (en lugar de usar las de MainActivity)
        private const val AQI_BACKUP_NOTIFICATION_CHANNEL_ID = "aqi_backup_notifications"
        private const val AQI_BACKUP_NOTIFICATION_ID = 1003

        fun scheduleAlarm(context: Context, frequencyMinutes: Int) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, AQIAlarmReceiver::class.java).apply {
                action = ACTION_AQI_CHECK
                putExtra(EXTRA_FREQUENCY, frequencyMinutes)
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                1001,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val intervalMillis = frequencyMinutes * 60 * 1000L
            val triggerTime = SystemClock.elapsedRealtime() + intervalMillis

            try {
                // Usar setExactAndAllowWhileIdle para mayor confiabilidad
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                } else {
                    alarmManager.setExact(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                }
                Log.d(TAG, "Alarma programada para $frequencyMinutes minutos")
            } catch (e: SecurityException) {
                Log.e(TAG, "No se pudo programar alarma exacta, usando setWindow", e)
                // Fallback para dispositivos muy restrictivos
                alarmManager.setWindow(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerTime,
                    intervalMillis / 4, // Ventana de 25% del intervalo
                    pendingIntent
                )
            }
        }

        fun cancelAlarm(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, AQIAlarmReceiver::class.java).apply {
                action = ACTION_AQI_CHECK
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                1001,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            alarmManager.cancel(pendingIntent)
            Log.d(TAG, "Alarma cancelada")
        }
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        when (intent.action) {
            ACTION_AQI_CHECK -> {
                val frequency = intent.getIntExtra(EXTRA_FREQUENCY, 30)

                Log.d(TAG, "Alarma AQI disparada")

                // Crear canal de notificación si no existe
                createBackupNotificationChannel(context)

                // Programar la siguiente alarma inmediatamente
                scheduleAlarm(context, frequency)

                // Verificar y enviar notificación en una corrutina
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        checkAndSendAQINotification(context)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error en verificación AQI", e)
                    }
                }
            }
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_PACKAGE_REPLACED -> {
                val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                val enabled = prefs.getBoolean(MainActivity.PREF_NOTIFICATIONS_ENABLED, false)
                if (enabled) {
                    val freq = prefs.getInt(MainActivity.PREF_NOTIFICATION_FREQUENCY, 30)
                    NotificationScheduler.scheduleNotifications(context, freq)
                }
            }
        }
    }

    private fun createBackupNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                AQI_BACKUP_NOTIFICATION_CHANNEL_ID,
                "Notificaciones AQI Backup",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notificaciones de respaldo sobre la calidad del aire"
            }

            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private suspend fun checkAndSendAQINotification(context: Context) {
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

                    sendAQINotification(context, aqi, aqiLevel)
                    Log.d(TAG, "Notificación AQI enviada desde alarma: $aqi ($aqiLevel)")
                } else {
                    Log.w(TAG, "No hay datos de usuario disponibles para notificación")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error enviando notificación AQI desde alarma", e)
            }
        }
    }

    private fun sendAQINotification(context: Context, aqi: Int, aqiLevel: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
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

        val builder = NotificationCompat.Builder(context, AQI_BACKUP_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("$icon BreatheSafe (Backup)")
            .setContentText(notificationText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notificationText))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        try {
            with(NotificationManagerCompat.from(context)) {
                notify(AQI_BACKUP_NOTIFICATION_ID, builder.build())
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "No se pudieron enviar notificaciones desde alarma: falta permiso", e)
        }
    }
}