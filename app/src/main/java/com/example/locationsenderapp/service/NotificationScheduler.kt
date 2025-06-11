package com.example.locationsenderapp.service

import android.content.Context
import android.util.Log

object NotificationScheduler {
    private const val TAG = "NotificationScheduler"

    fun scheduleNotifications(context: Context, frequency: Int, useAlarmBackup: Boolean = true) {
        // Método principal: Foreground Service
        AQIForegroundService.startService(context, frequency)

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