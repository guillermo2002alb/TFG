package com.example.locationsenderapp.service

import android.content.Context
import android.util.Log
import android.content.Intent
import com.example.locationsenderapp.service.TTNDataService

object NotificationScheduler {
    private const val TAG = "NotificationScheduler"


    fun scheduleNotifications(context: Context, frequency: Int, useAlarmBackup: Boolean = true) {
        // Método principal: Foreground Service
        AQIForegroundService.startService(context, frequency)

        // Iniciar servicio TTN para mantener la conexión de datos
        try {
            val serviceIntent = Intent(context, TTNDataService::class.java)
            context.startService(serviceIntent)
            Log.d(TAG, "TTNDataService iniciado desde NotificationScheduler")
        } catch (e: Exception) {
            Log.e(TAG, "Error iniciando TTNDataService", e)
        }


        // Método de respaldo: AlarmManager (opcional)
        if (useAlarmBackup) {
            AQIAlarmReceiver.scheduleAlarm(context, frequency)
        }

        Log.d(TAG, "Notificaciones programadas: Service + Alarm backup")
    }

    fun cancelNotifications(context: Context) {
        // Cancelar servicio foreground
        AQIForegroundService.stopService(context)

        // Cancelar alarmas
        AQIAlarmReceiver.cancelAlarm(context)

        Log.d(TAG, "Notificaciones canceladas")
    }
}