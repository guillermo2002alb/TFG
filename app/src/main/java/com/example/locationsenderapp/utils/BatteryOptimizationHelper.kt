// BatteryOptimizationHelper.kt
package com.example.locationsenderapp.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

object BatteryOptimizationHelper {

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            true // No hay optimizaciones en versiones anteriores
        }
    }

    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                // Si falla, abrir configuración general
                openBatteryOptimizationSettings(context)
            }
        }
    }

    fun openBatteryOptimizationSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            context.startActivity(intent)
        } catch (e: Exception) {
            // Fallback a configuración general
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        }
    }

    fun getManufacturerSpecificInstructions(context: Context): String {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return when {
            manufacturer.contains("xiaomi") ->
                "XIAOMI/MIUI:\n" +
                        "1. Ve a Configuración > Aplicaciones > BreatheSafe\n" +
                        "2. Toca 'Permisos' > 'Autostart'\n" +
                        "3. Habilita 'Autostart'\n" +
                        "4. Ve a 'Ahorro de batería' y selecciona 'Sin restricciones'"

            manufacturer.contains("huawei") ->
                "HUAWEI/EMUI:\n" +
                        "1. Ve a Configuración > Aplicaciones > BreatheSafe\n" +
                        "2. Toca 'Batería' > 'Inicio de aplicaciones'\n" +
                        "3. Desactiva 'Gestionar automáticamente'\n" +
                        "4. Habilita todas las opciones manualmente"

            manufacturer.contains("oppo") ->
                "OPPO/ColorOS:\n" +
                        "1. Ve a Configuración > Batería > Optimización de energía\n" +
                        "2. Busca BreatheSafe y desactiva optimización\n" +
                        "3. Ve a 'Administrador de aplicaciones' > BreatheSafe\n" +
                        "4. Habilita 'Permitir en segundo plano'"

            manufacturer.contains("vivo") ->
                "VIVO/FunTouch:\n" +
                        "1. Ve a Configuración > Batería > Administración de energía\n" +
                        "2. Busca BreatheSafe en 'Aplicaciones con alto consumo'\n" +
                        "3. Desactiva la optimización\n" +
                        "4. En 'Administrador de aplicaciones' habilita 'Ejecutar en segundo plano'"

            manufacturer.contains("oneplus") ->
                "ONEPLUS/OxygenOS:\n" +
                        "1. Ve a Configuración > Batería > Optimización de batería\n" +
                        "2. Busca BreatheSafe y selecciona 'No optimizar'\n" +
                        "3. Ve a Configuración > Aplicaciones > BreatheSafe\n" +
                        "4. Habilita 'Permitir actividad en segundo plano'"

            else ->
                "ANDROID ESTÁNDAR:\n" +
                        "1. Ve a Configuración > Aplicaciones > BreatheSafe\n" +
                        "2. Toca 'Batería' > 'Optimización de batería'\n" +
                        "3. Busca BreatheSafe y selecciona 'No optimizar'\n" +
                        "4. Confirma los cambios"
        }
    }
}