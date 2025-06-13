package com.example.locationsenderapp

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import java.util.concurrent.TimeUnit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.KeyboardType
import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Intent
import com.example.locationsenderapp.data.StationDatabase
import com.example.locationsenderapp.data.StationRepository
import android.content.pm.PackageManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.locationsenderapp.service.GranadaAirQualityService
import com.example.locationsenderapp.service.TTNDataService
import com.example.locationsenderapp.service.AQIForegroundService
import com.example.locationsenderapp.service.NotificationScheduler
import com.example.locationsenderapp.utils.BatteryOptimizationHelper
import com.example.locationsenderapp.ui.components.BatteryOptimizationDialog
import com.example.locationsenderapp.ui.theme.LocationSenderAppTheme
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.*
import com.google.android.gms.maps.MapsInitializer
import com.google.android.gms.maps.model.*
import com.google.maps.android.compose.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*
import android.net.Uri
import androidx.compose.material.icons.filled.Analytics

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "BreatheSafe"
        private const val REQUEST_LOCATION_PERMISSIONS = 1001
        private const val REQUEST_NOTIFICATION_PERMISSION = 1002
        private const val NOTIFICATION_CHANNEL_ID = "aqi_notifications"
        private const val NOTIFICATION_ID = 1001
        private const val WORK_NAME = "aqi_notification_work"

        // Preferencias compartidas
        // Preferencias compartidas (visibles para otros componentes)
        const val PREFS_NAME = "breathe_safe_prefs"
        const val PREF_NOTIFICATIONS_ENABLED = "notifications_enabled"
        const val PREF_NOTIFICATION_FREQUENCY = "notification_frequency"

        // Lista de sensores con sus datos
        val sensorsData = mutableStateMapOf<String, SensorInfo>()

        // Variable para compartir datos entre servicios
        @Volatile
        var currentTTNData: TTNSensorData? = null
    }

    // Estados de la UI
    private var myLocation by mutableStateOf<LatLng?>(null)
    private var lastUpdateTime by mutableStateOf<Long?>(null)
    private var ttnStatus by mutableStateOf("Conectando a TTN...")
    private var ttnConnected by mutableStateOf(false)
    private var showMap by mutableStateOf(false)

    // Servicio para datos de Granada
    // Repositorio y servicio para datos de Granada
    private lateinit var stationRepository: StationRepository
    private lateinit var granadaAirService: GranadaAirQualityService

    private var userEmail by mutableStateOf("")

    private var showSettingsDialog by mutableStateOf(false)
    private var showBatteryOptimizationDialog by mutableStateOf(false)
    private var notificationsEnabled by mutableStateOf(false)
    private var notificationFrequency by mutableStateOf(30) // minutos por defecto
    private lateinit var sharedPreferences: SharedPreferences

    // Cliente para ubicación
    private val fusedLocationClient by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Inicializar SharedPreferences
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        notificationsEnabled = sharedPreferences.getBoolean(PREF_NOTIFICATIONS_ENABLED, false)
        notificationFrequency = sharedPreferences.getInt(PREF_NOTIFICATION_FREQUENCY, 30)


        stationRepository = StationRepository(StationDatabase.getDatabase(this).stationDataDao())
        granadaAirService = GranadaAirQualityService().apply {
            onStationData = { sensor ->
                lifecycleScope.launch {
                    stationRepository.insert(StationRepository.fromSensorInfo(sensor))
                }
            }
        }

        createNotificationChannel()

        // Solicitar permisos de notificación para Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission()
        }

        // Inicializar MapsInitializer para asegurar que Maps esté listo
        try {
            MapsInitializer.initialize(
                applicationContext,
                MapsInitializer.Renderer.LATEST
            ) { renderer ->
                Log.d(TAG, "Maps renderer: $renderer")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error inicializando Maps", e)
        }

        // Verificar Google Play Services
        val availability = GoogleApiAvailability.getInstance()
        val resultCode = availability.isGooglePlayServicesAvailable(this)

        when (resultCode) {
            ConnectionResult.SUCCESS -> {
                Log.d(TAG, "✅ Google Play Services disponible")
            }
            ConnectionResult.SERVICE_MISSING -> {
                Log.e(TAG, "❌ Google Play Services no instalado")
                availability.getErrorDialog(this, resultCode, 1)?.show()
            }
            ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED -> {
                Log.e(TAG, "❌ Google Play Services necesita actualización")
                availability.getErrorDialog(this, resultCode, 1)?.show()
            }
            ConnectionResult.SERVICE_DISABLED -> {
                Log.e(TAG, "❌ Google Play Services deshabilitado")
                availability.getErrorDialog(this, resultCode, 1)?.show()
            }
            else -> {
                Log.e(TAG, "❌ Error Google Play Services: $resultCode")
            }
        }

        enableEdgeToEdge()

        setContent {
            LocationSenderAppTheme {
                if (showMap) {
                    MapScreen()
                } else {
                    MainScreen()
                }

                // Diálogo de optimización de batería
                if (showBatteryOptimizationDialog) {
                    BatteryOptimizationDialog(
                        onDismiss = { showBatteryOptimizationDialog = false }
                    )
                }
            }
        }

        // Verificar permisos al inicio
        checkAndRequestPermissions()

        // Iniciar servicio TTN
        startTTNService()

        // Iniciar servicio de datos de Granada
        granadaAirService.startPolling()




        // Obtener ubicación del usuario con interpolación
        getCurrentLocationWithInterpolation()

        // Verificar datos TTN periódicamente
        lifecycleScope.launch {
            while (true) {
                delay(1000) // Verificar cada segundo

                // Verificar si hay nuevos datos de TTN
                currentTTNData?.let { data ->
                    if (data.timestamp > (lastUpdateTime ?: 0)) {
                        // Crear o actualizar información del sensor
                        val sensorInfo = SensorInfo(
                            deviceId = data.deviceId,
                            location = LatLng(data.latitude, data.longitude),
                            pollutionData = PollutionData(
                                name = data.deviceId,
                                pm1_0 = data.pm1_0,
                                pm2_5 = data.pm2_5,
                                pm4_0 = data.pm4_0,
                                pm10 = data.pm10,
                                o3 = 0.0, // TTN no envía O3
                                co = data.co,
                                aqi = 0.0, // TTN no envía AQI
                                temperature = data.temperature,
                                humidity = data.humidity,
                                vocIndex = data.vocIndex,
                                noxIndex = data.noxIndex,
                                co2 = data.co2,
                                no2 = data.no2,
                                so2 = 0.0
                            ),
                            lastUpdate = System.currentTimeMillis()
                        )

                        // Actualizar el mapa de sensores
                        sensorsData[data.deviceId] = sensorInfo

                        Log.i(
                            TAG,
                            "Datos actualizados para sensor ${data.deviceId}: ${sensorInfo.pollutionData}"
                        )

                        // Actualizar timestamp y estado
                        lastUpdateTime = data.timestamp
                        ttnConnected = true

                        // Recalcular interpolación del usuario
                        updateUserLocationData()

                        // Contar todos los sensores (TTN + Granada)
                        val ttnSensors =
                            sensorsData.count { !it.key.contains("granada-") && it.key != "user-location" }
                        val granadaSensors = sensorsData.count { it.key.contains("granada-") }
                        ttnStatus =
                            "TTN: $ttnSensors sensor(es) | Granada: $granadaSensors estación(es)"
                    }
                }

                // Actualizar la ubicación del usuario cada 30 segundos
                if (System.currentTimeMillis() - (lastUpdateTime ?: 0) > 30000) {
                    getCurrentLocationWithInterpolation()
                }
            }
        }

        // Verificar optimización de batería después de cargar la UI
        if (notificationsEnabled && !BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)) {
            lifecycleScope.launch {
                delay(2000) // Dar tiempo para que la UI se cargue
                showBatteryOptimizationDialog = true
            }
        }

        // Iniciar servicio foreground si está habilitado
        if (notificationsEnabled) {
            NotificationScheduler.scheduleNotifications(this, notificationFrequency)
            Log.d(TAG, "Servicios de notificación iniciados automáticamente")
        }
    }

    // Función para calcular la distancia entre dos puntos en km usando la fórmula de Haversine
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371.0 // Radio de la Tierra en km
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    // Función para calcular valores interpolados por distancia inversa ponderada
    private fun calculateInterpolatedValues(userLocation: LatLng): PollutionData? {
        if (sensorsData.isEmpty()) return null

        // Filtrar solo las estaciones con datos válidos (excluyendo la ubicación del usuario)
        val validStations = sensorsData.values.filter { sensor ->
            sensor.deviceId != "user-location"
        }

        if (validStations.isEmpty()) return null

        // Calcular distancias y pesos para cada estación
        data class StationWeight(
            val sensor: SensorInfo,
            val distance: Double,
            val weight: Double
        )

        val stationWeights = validStations.map { sensor ->
            val distance = calculateDistance(
                userLocation.latitude, userLocation.longitude,
                sensor.location.latitude, sensor.location.longitude
            )

            // Usar distancia mínima de 0.1 km para evitar división por cero
            val effectiveDistance = maxOf(distance, 0.1)

            // Peso = 1 / distancia^2 (método IDW)
            val weight = 1.0 / (effectiveDistance * effectiveDistance)

            StationWeight(sensor, effectiveDistance, weight)
        }.sortedBy { it.distance }

        Log.d(
            TAG,
            "Calculando interpolación para ubicación: ${userLocation.latitude}, ${userLocation.longitude}"
        )
        stationWeights.forEach { sw ->
            Log.d(
                TAG,
                "  ${sw.sensor.deviceId}: distancia=${
                    String.format(
                        "%.2f",
                        sw.distance
                    )
                }km, peso=${String.format("%.4f", sw.weight)}"
            )
        }

        // Función auxiliar para interpolar un contaminante específico con conversión ICA
        fun interpolateValueWithICA(
            getValue: (PollutionData) -> Double,
            contaminantType: String
        ): Double {
            val validValues = stationWeights.mapNotNull { sw ->
                val rawValue = getValue(sw.sensor.pollutionData)
                if (rawValue > 0) {
                    // Convertir a ICA según el tipo de estación y contaminante
                    val icaValue = when {
                        // Para estación my-tfg-2025: convertir CO2 además de los otros contaminantes
                        sw.sensor.deviceId.contains("my-tfg-2025") && contaminantType == "CO2" -> {
                            convertToICA("CO2", rawValue)
                        }
                        // Para estación sensor-sinco2: convertir todos los contaminantes normales
                        sw.sensor.deviceId.contains("sensor-sinco2") -> {
                            convertToICA(contaminantType, rawValue)
                        }
                        // Para ambas estaciones: convertir contaminantes estándar
                        (sw.sensor.deviceId.contains("my-tfg-2025") || sw.sensor.deviceId.contains("sensor-sinco2")) &&
                                contaminantType in listOf(
                            "CO",
                            "NO2",
                            "PM10",
                            "PM4",
                            "PM25",
                            "PM1"
                        ) -> {
                            convertToICA(contaminantType, rawValue)
                        }
                        // Para otras estaciones o contaminantes no convertibles, usar valor raw
                        else -> rawValue
                    }
                    Pair(icaValue, sw.weight)
                } else null
            }

            return if (validValues.isNotEmpty()) {
                val weightedSum = validValues.sumOf { it.first * it.second }
                val totalWeight = validValues.sumOf { it.second }
                weightedSum / totalWeight
            } else {
                0.0
            }
        }

        // Función auxiliar para interpolar valores sin conversión ICA (temperatura, humedad, índices)
        fun interpolateValue(getValue: (PollutionData) -> Double): Double {
            val validValues = stationWeights.mapNotNull { sw ->
                val value = getValue(sw.sensor.pollutionData)
                if (value > 0) Pair(value, sw.weight) else null
            }

            return if (validValues.isNotEmpty()) {
                val weightedSum = validValues.sumOf { it.first * it.second }
                val totalWeight = validValues.sumOf { it.second }
                weightedSum / totalWeight
            } else {
                0.0
            }
        }

        // Calcular valores interpolados para cada contaminante
        val interpolatedData = PollutionData(
            name = "Estimación (Mi Ubicación)",
            pm1_0 = interpolateValueWithICA({ it.pm1_0 }, "PM1"),
            pm2_5 = interpolateValueWithICA({ it.pm2_5 }, "PM25"),
            pm4_0 = interpolateValueWithICA({ it.pm4_0 }, "PM4"),
            pm10 = interpolateValueWithICA({ it.pm10 }, "PM10"),
            temperature = interpolateValue { it.temperature }, // Sin conversión ICA
            humidity = interpolateValue { it.humidity }, // Sin conversión ICA
            vocIndex = interpolateValue { it.vocIndex }, // Sin conversión ICA
            noxIndex = interpolateValue { it.noxIndex }, // Sin conversión ICA
            o3 = interpolateValue { it.o3 }, // Sin conversión ICA por ahora
            co = interpolateValueWithICA({ it.co }, "CO"),
            aqi = 0.0, // Se calculará después
            co2 = interpolateValueWithICA({ it.co2 }, "CO2"),
            no2 = interpolateValueWithICA({ it.no2 }, "NO2"),
            so2 = interpolateValue { it.so2 } // Sin conversión ICA por ahora
        )

        Log.d(TAG, "Valores interpolados calculados (en ICA como se muestran):")
        Log.d(TAG, "  AQI: ${String.format("%.1f", interpolatedData.aqi)}")
        Log.d(TAG, "  PM2.5: ${String.format("%.1f", interpolatedData.pm2_5)}")
        Log.d(TAG, "  PM10: ${String.format("%.1f", interpolatedData.pm10)}")
        Log.d(TAG, "  NO2: ${String.format("%.1f", interpolatedData.no2)}")
        Log.d(TAG, "  CO: ${String.format("%.1f", interpolatedData.co)}")
        Log.d(TAG, "  CO2: ${String.format("%.1f", interpolatedData.co2)}")
        Log.d(TAG, "  Temperatura: ${String.format("%.1f", interpolatedData.temperature)}°C")

        return interpolatedData
    }

    // Función para actualizar los datos del usuario con valores interpolados
    private fun updateUserLocationData() {
        myLocation?.let { userLoc ->
            val interpolatedData = calculateInterpolatedValues(userLoc)

            if (interpolatedData != null) {
                val userSensorInfo = SensorInfo(
                    deviceId = "user-location",
                    location = userLoc,
                    pollutionData = interpolatedData,
                    lastUpdate = System.currentTimeMillis()
                )

                // Actualizar el mapa de sensores con los datos del usuario
                sensorsData["user-location"] = userSensorInfo

                Log.d(TAG, "Datos de usuario actualizados con interpolación")
            }
        }
    }

    // Función getCurrentLocation() modificada para incluir interpolación
    @SuppressLint("MissingPermission")
    private fun getCurrentLocationWithInterpolation() {
        if (!hasLocationPermissions()) {
            return
        }

        fusedLocationClient.lastLocation
            .addOnSuccessListener { location ->
                if (location != null) {
                    myLocation = LatLng(location.latitude, location.longitude)
                    Log.i(TAG, "Ubicación del usuario: ${location.latitude}, ${location.longitude}")

                    // Calcular y actualizar datos interpolados
                    updateUserLocationData()
                } else {
                    // Si no hay última ubicación conocida, obtener una nueva
                    fusedLocationClient.getCurrentLocation(
                        Priority.PRIORITY_HIGH_ACCURACY,
                        null
                    ).addOnSuccessListener { loc ->
                        if (loc != null) {
                            myLocation = LatLng(loc.latitude, loc.longitude)
                            Log.i(TAG, "Ubicación del usuario obtenida: ${loc.latitude}, ${loc.longitude}")
                            updateUserLocationData()
                        } else {
                            Log.w(TAG, "No se pudo obtener ubicación actual")
                        }
                    }.addOnFailureListener { e ->
                        Log.e(TAG, "Error obteniendo ubicación actual", e)
                    }
                }
            }
            .addOnFailureListener { e ->

                Log.e(TAG, "Error obteniendo última ubicación", e)
            }
    }

    @Composable
    fun SettingsDialog(
        notificationsEnabled: Boolean,
        notificationFrequency: Int,
        onNotificationsEnabledChange: (Boolean) -> Unit,
        onFrequencyChange: (Int) -> Unit,

        onDismiss: () -> Unit
    ) {
        var tempFrequency by remember { mutableStateOf(notificationFrequency.toString()) }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = null,
                        tint = Color(0xFF2E7D32)
                    )
                    Text(
                        text = "Configuración",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Activar/desactivar notificaciones
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Notificaciones AQI",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Mantiene el servicio activo en segundo plano",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                        Switch(
                            checked = notificationsEnabled,
                            onCheckedChange = onNotificationsEnabledChange
                        )
                    }

                    HorizontalDivider()

                    // Configurar frecuencia
                    if (notificationsEnabled) {
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.Notifications,
                                    contentDescription = null,
                                    tint = Color(0xFF2E7D32)
                                )
                                Text(
                                    text = "Frecuencia de notificaciones",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedTextField(
                                value = tempFrequency,
                                onValueChange = { newValue ->
                                    // Solo permitir números
                                    if (newValue.all { it.isDigit() } && newValue.length <= 3) {
                                        tempFrequency = newValue
                                    }
                                },
                                label = { Text("Minutos") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Text(
                                text = "Rango: 1-999 minutos",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )

                            Spacer(modifier = Modifier.height(8.dp))



                            Spacer(modifier = Modifier.height(8.dp))


                            // Información adicional sobre el servicio
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = Color(0xFF2E7D32).copy(alpha = 0.1f)
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp)
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.Info,
                                            contentDescription = null,
                                            tint = Color(0xFF2E7D32),
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            text = "Servicio en segundo plano",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Medium,
                                            color = Color(0xFF2E7D32)
                                        )
                                    }
                                    Text(
                                        text = "Mantiene las notificaciones activas incluso cuando la aplicación está cerrada",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.Gray
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val frequency = tempFrequency.toIntOrNull()
                        if (frequency != null && frequency in 1..999) {
                            onFrequencyChange(frequency)
                        }

                        onDismiss()
                    }
                ) {
                    Text("Guardar")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cancelar")
                }
            }
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainScreen() {
        // Colores basados en el logo BreatheSafe
        val gradientColors = listOf(
            Color(0xFF1A237E), // Azul marino profundo
            Color(0xFF3949AB)  // Azul índigo
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(gradientColors))
        ) {
            // Header con botones de configuración y batería
            TopAppBar(
                title = {
                    Text(
                        text = "BreatheSafe",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp
                    )
                },
                actions = {
                    // Solo botón de configuración (eliminar el de batería)
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Configuración",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Spacer(modifier = Modifier.height(20.dp))

                // Logo e información
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Logo BreatheSafe con diseño más profesional
                    Box(
                        contentAlignment = Alignment.Center
                    ) {
                        // Fondo circular con gradiente más sutil
                        Box(
                            modifier = Modifier
                                .size(100.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(
                                            Color(0xFF64B5F6), // Azul claro
                                            Color(0xFF1E88E5)  // Azul medio
                                        )
                                    )
                                )
                        )

                        // Icono más profesional
                        Icon(
                            imageVector = Icons.Default.Air,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(50.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Sistema de Monitoreo de Calidad del Aire",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Medium
                    )

                    Text(
                        text = "Universidad de Granada",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.9f),
                        textAlign = TextAlign.Center
                    )
                }

                // Card de estado con información del servicio
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color.White.copy(alpha = 0.95f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Estado del Sistema",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )

                            StatusIndicator(ttnConnected)
                        }

                        HorizontalDivider(color = Color.Gray.copy(alpha = 0.2f))

                        // Estado del servicio foreground
                        if (notificationsEnabled) {
                            LocationInfo(
                                icon = Icons.Default.Notifications,
                                title = "Servicio de Notificaciones",
                                content = if (isAQIServiceRunning()) {
                                    "Activo - Monitoreando cada $notificationFrequency min"
                                } else {
                                    "Iniciando servicio..."
                                },
                                iconColor = if (isAQIServiceRunning()) Color(0xFF4CAF50) else Color(0xFFFF9800)
                            )

                            // Advertencia de optimización de batería
                            if (!BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this@MainActivity)) {
                                LocationInfo(
                                    icon = Icons.Default.BatteryAlert,
                                    title = "Optimización de Batería",
                                    content = "Puede afectar las notificaciones - Toca para configurar",
                                    iconColor = Color(0xFFFF6F00)
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        // Estado TTN
                        LocationInfo(
                            icon = Icons.Default.Sensors,
                            title = "Conexión TTN",
                            content = ttnStatus,
                            iconColor = Color(0xFF2E7D32)
                        )

                        // Mostrar datos interpolados del usuario si está disponible
                        sensorsData["user-location"]?.let { userSensor ->
                            LocationInfo(
                                icon = Icons.Default.MyLocation,
                                title = "Mi Ubicación (Estimado)",
                                content = run {
                                    val aqi = if (userSensor.pollutionData.name.contains("Estimación")) {
                                        val individualAqis = mutableListOf<Double>()

                                        if (userSensor.pollutionData.co > 0) individualAqis.add(userSensor.pollutionData.co)
                                        if (userSensor.pollutionData.co2 > 0) individualAqis.add(userSensor.pollutionData.co2)
                                        if (userSensor.pollutionData.so2 > 0) individualAqis.add(userSensor.pollutionData.so2)
                                        if (userSensor.pollutionData.o3 > 0) individualAqis.add(userSensor.pollutionData.o3)
                                        if (userSensor.pollutionData.no2 > 0) individualAqis.add(userSensor.pollutionData.no2)
                                        if (userSensor.pollutionData.pm10 > 0) individualAqis.add(userSensor.pollutionData.pm10)
                                        if (userSensor.pollutionData.pm4_0 > 0) individualAqis.add(userSensor.pollutionData.pm4_0)
                                        if (userSensor.pollutionData.pm2_5 > 0) individualAqis.add(userSensor.pollutionData.pm2_5)
                                        if (userSensor.pollutionData.pm1_0 > 0) individualAqis.add(userSensor.pollutionData.pm1_0)

                                        individualAqis.maxOrNull()?.toInt() ?: userSensor.pollutionData.aqi.toInt()
                                    } else {
                                        userSensor.pollutionData.aqi.toInt() // Retorna el AQI del sensor directamente
                                    }

                                    val aqiLevel = when {
                                        aqi <= 33 -> "Muy Buena"
                                        aqi <= 66 -> "Buena"
                                        aqi <= 100 -> "Regular"
                                        aqi <= 200 -> "Mala"
                                        else -> "Muy Mala"
                                    }
                                    "AQI: $aqi - $aqiLevel (Interpolado)"
                                },
                                iconColor = Color(0xFF2196F3) // Azul para usuario
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        // Información de los sensores
                        if (sensorsData.isNotEmpty()) {
                            sensorsData.forEach { (deviceId, sensorInfo) ->
                                // Saltar la ubicación del usuario ya mostrada arriba
                                if (deviceId == "user-location") return@forEach

                                val iconColor = when {
                                    deviceId.contains("granada-") -> Color(0xFF9C27B0) // Violeta
                                    deviceId.contains("sinco2") -> Color(0xFFFF6F00) // Naranja
                                    else -> Color(0xFF2E7D32) // Verde
                                }

                                val title = when {
                                    deviceId.contains("granada-") -> "Estación: ${sensorInfo.pollutionData.name}"
                                    deviceId.contains("sinco2") -> "Estación: ETSIIT"
                                    deviceId.contains("tfg") -> "Estación: Camino de Ronda"
                                    else -> "Sensor: $deviceId"
                                }

                                val content = when {
                                    deviceId.contains("granada-") -> {
                                        val aqi = sensorInfo.pollutionData.aqi.toInt()
                                        val aqiLevel = when {
                                            aqi <= 33 -> "Muy Buena"
                                            aqi <= 66 -> "Buena"
                                            aqi <= 100 -> "Regular"
                                            aqi <= 200 -> "Mala"
                                            else -> "Muy Mala"
                                        }
                                        "AQI: $aqi - $aqiLevel"
                                    }

                                    else -> {
                                        val aqi = if (sensorInfo.pollutionData.name.contains("granada")) {
                                            sensorInfo.pollutionData.aqi.toInt() // Eliminé la declaración duplicada
                                        } else {
                                            val individualAqis = mutableListOf<Double>()

                                            if (sensorInfo.pollutionData.co > 0) individualAqis.add(convertToICA("CO", sensorInfo.pollutionData.co))
                                            if (sensorInfo.pollutionData.name.contains("my-tfg-2025") && sensorInfo.pollutionData.co2 > 0) {
                                                individualAqis.add(convertToICA("CO2", sensorInfo.pollutionData.co2))
                                            }
                                            if (sensorInfo.pollutionData.no2 > 0) individualAqis.add(convertToICA("NO2", sensorInfo.pollutionData.no2))
                                            if (sensorInfo.pollutionData.pm10 > 0) individualAqis.add(convertToICA("PM10", sensorInfo.pollutionData.pm10))
                                            if (sensorInfo.pollutionData.pm4_0 > 0) individualAqis.add(convertToICA("PM4", sensorInfo.pollutionData.pm4_0))
                                            if (sensorInfo.pollutionData.pm2_5 > 0) individualAqis.add(convertToICA("PM25", sensorInfo.pollutionData.pm2_5))
                                            if (sensorInfo.pollutionData.pm1_0 > 0) individualAqis.add(convertToICA("PM1", sensorInfo.pollutionData.pm1_0))

                                            individualAqis.maxOrNull()?.toInt() ?: sensorInfo.pollutionData.aqi.toInt()
                                        }

                                        val aqiLevel = when {
                                            aqi <= 33 -> "Muy Buena"
                                            aqi <= 66 -> "Buena"
                                            aqi <= 100 -> "Regular"
                                            aqi <= 200 -> "Mala"
                                            else -> "Muy Mala"
                                        }
                                        "AQI: $aqi - $aqiLevel"
                                    }
                                }

                                LocationInfo(
                                    icon = Icons.Default.Sensors,
                                    title = title,
                                    content = content,
                                    iconColor = iconColor
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                            }


                            // Última actualización
                            lastUpdateTime?.let {
                                LocationInfo(
                                    icon = Icons.Default.Update,
                                    title = "Última Actualización",
                                    content = SimpleDateFormat(
                                        "dd/MM/yyyy HH:mm:ss",
                                        Locale.getDefault()
                                    ).format(Date(it)),
                                    iconColor = Color(0xFF1976D2)
                                )
                            }
                        }
                    }
                }

                // Botón para ver el mapa
                Button(
                    onClick = { showMap = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1976D2) // Azul profesional
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Map,
                            contentDescription = null,
                            tint = Color.White
                        )
                        Text(
                            text = "Ver Mapa de Contaminación",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://rt.ugr.es:8953/dashboard/53b27400-4846-11f0-8e5b-6bfecd626acc?publicId=3b734db0-29bf-11f0-8e5b-6bfecd626acc"))
                        startActivity(intent)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF0D47A1) // Azul más oscuro
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Analytics,
                            contentDescription = null,
                            tint = Color.White
                        )
                        Text(
                            text = "Ver Estadísticas",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))
                // Footer
                Text(
                    text = "Powered by The Things Network & WAQI",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }
        }

        // Diálogo de configuración
        if (showSettingsDialog) {
            SettingsDialog(
                notificationsEnabled = notificationsEnabled,
                notificationFrequency = notificationFrequency,
                onNotificationsEnabledChange = { enabled ->
                    notificationsEnabled = enabled
                    savePreferences()

                    if (enabled) {
                        NotificationScheduler.scheduleNotifications(this@MainActivity, notificationFrequency)

                        // Verificar optimización de batería
                        if (!BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this@MainActivity)) {
                            showBatteryOptimizationDialog = true
                        }

                        Log.d(TAG, "Servicios de notificación iniciados")
                    } else {
                        NotificationScheduler.cancelNotifications(this@MainActivity)
                        WorkManager.getInstance(this@MainActivity).cancelUniqueWork(WORK_NAME)
                        Log.d(TAG, "Servicios de notificación detenidos")
                    }
                },
                onFrequencyChange = { frequency ->
                    notificationFrequency = frequency
                    savePreferences()

                    if (notificationsEnabled) {
                        // Reiniciar servicios con nueva frecuencia
                        NotificationScheduler.cancelNotifications(this@MainActivity)
                        lifecycleScope.launch {
                            delay(1000) // Dar tiempo para que se detengan
                            NotificationScheduler.scheduleNotifications(this@MainActivity, frequency)
                        }
                        Log.d(TAG, "Servicios reiniciados con frecuencia: $frequency min")
                    }
                },
                onDismiss = { showSettingsDialog = false }
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MapScreen() {
        val context = LocalContext.current
        var selectedMarker by remember { mutableStateOf<String?>(null) }
        var mapLoaded by remember { mutableStateOf(false) }
        var mapError by remember { mutableStateOf<String?>(null) }

        val gradientColors = listOf(
            Color(0xFF2E7D32), // Verde oscuro
            Color(0xFF43A047)  // Verde medio
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(gradientColors))
        ) {
            // Top Bar
            TopAppBar(
                title = {
                    Text(
                        text = "Mapa de Contaminación",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { showMap = false }) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Volver",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )

            Box(modifier = Modifier.fillMaxSize()) {
                // Si hay error, mostrarlo
                mapError?.let { error ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = Color.Red
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Error al cargar el mapa",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White
                        )
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                mapError = null
                                mapLoaded = false
                            }
                        ) {
                            Text("Reintentar")
                        }
                    }
                }

                // Mostrar indicador de carga
                if (!mapLoaded && mapError == null) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Cargando mapa...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White
                            )
                        }
                    }
                }

                // Estado de la cámara del mapa - centrado en Granada
                val cameraPositionState = rememberCameraPositionState {
                    position = CameraPosition.fromLatLngZoom(
                        LatLng(37.1773, -3.6082), // Granada centro
                        12f // Zoom más alejado para ver ambos sensores
                    )
                }

                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = cameraPositionState,
                    properties = MapProperties(
                        isMyLocationEnabled = hasLocationPermissions(),
                        mapType = MapType.NORMAL
                    ),
                    uiSettings = MapUiSettings(
                        zoomControlsEnabled = true,
                        myLocationButtonEnabled = hasLocationPermissions()
                    ),
                    onMapLoaded = {
                        mapLoaded = true
                        Log.d(TAG, "✅ Mapa cargado correctamente")
                    }
                ) {
                    // Marcadores para cada sensor
                    sensorsData.forEach { (deviceId, sensorInfo) ->
                        val (markerColor, zIndex) = when {
                            deviceId == "user-location" -> BitmapDescriptorFactory.HUE_BLUE to 3f // Usuario con prioridad alta
                            deviceId.contains("granada-") -> BitmapDescriptorFactory.HUE_VIOLET to 1f // Estaciones de Granada
                            deviceId.contains("sinco2") -> BitmapDescriptorFactory.HUE_ORANGE to 2f
                            deviceId.contains("tfg") -> BitmapDescriptorFactory.HUE_GREEN to 2f
                            else -> BitmapDescriptorFactory.HUE_RED to 2f
                        }

                        Marker(
                            state = MarkerState(position = sensorInfo.location),
                            title = if (deviceId == "user-location") "Mi ubicación (Estimado)" else sensorInfo.pollutionData.name,
                            snippet = when {
                                deviceId == "user-location" -> {
                                    val aqi =
                                        if (sensorInfo.pollutionData.name.contains("Estimación")) {
                                            val individualAqis = mutableListOf<Double>()

                                            if (sensorInfo.pollutionData.co > 0) individualAqis.add(
                                                sensorInfo.pollutionData.co
                                            )
                                            if (sensorInfo.pollutionData.co2 > 0) individualAqis.add(
                                                sensorInfo.pollutionData.co2
                                            )
                                            if (sensorInfo.pollutionData.so2 > 0) individualAqis.add(
                                                sensorInfo.pollutionData.so2
                                            )
                                            if (sensorInfo.pollutionData.o3 > 0) individualAqis.add(
                                                sensorInfo.pollutionData.o3
                                            )
                                            if (sensorInfo.pollutionData.no2 > 0) individualAqis.add(
                                                sensorInfo.pollutionData.no2
                                            )
                                            if (sensorInfo.pollutionData.pm10 > 0) individualAqis.add(
                                                sensorInfo.pollutionData.pm10
                                            )
                                            if (sensorInfo.pollutionData.pm4_0 > 0) individualAqis.add(
                                                sensorInfo.pollutionData.pm4_0
                                            )
                                            if (sensorInfo.pollutionData.pm2_5 > 0) individualAqis.add(
                                                sensorInfo.pollutionData.pm2_5
                                            )
                                            if (sensorInfo.pollutionData.pm1_0 > 0) individualAqis.add(
                                                sensorInfo.pollutionData.pm1_0
                                            )

                                            individualAqis.maxOrNull()?.toInt()
                                                ?: sensorInfo.pollutionData.aqi.toInt()
                                        } else {
                                            val aqi = 0
                                        }
                                    "AQI Estimado: $aqi"
                                }

                                deviceId.contains("granada-") -> {
                                    val aqi = sensorInfo.pollutionData.aqi.toInt()
                                    "AQI: $aqi"
                                }

                                else -> {
                                    val aqi = if (sensorInfo.pollutionData.name.contains("granada")) {
                                        sensorInfo.pollutionData.aqi.toInt() // Eliminé la declaración duplicada
                                    } else {
                                        val individualAqis = mutableListOf<Double>()

                                        if (sensorInfo.pollutionData.co > 0) individualAqis.add(convertToICA("CO", sensorInfo.pollutionData.co))
                                        if (sensorInfo.pollutionData.name.contains("my-tfg-2025") && sensorInfo.pollutionData.co2 > 0) {
                                            individualAqis.add(convertToICA("CO2", sensorInfo.pollutionData.co2))
                                        }
                                        if (sensorInfo.pollutionData.no2 > 0) individualAqis.add(convertToICA("NO2", sensorInfo.pollutionData.no2))
                                        if (sensorInfo.pollutionData.pm10 > 0) individualAqis.add(convertToICA("PM10", sensorInfo.pollutionData.pm10))
                                        if (sensorInfo.pollutionData.pm4_0 > 0) individualAqis.add(convertToICA("PM4", sensorInfo.pollutionData.pm4_0))
                                        if (sensorInfo.pollutionData.pm2_5 > 0) individualAqis.add(convertToICA("PM25", sensorInfo.pollutionData.pm2_5))
                                        if (sensorInfo.pollutionData.pm1_0 > 0) individualAqis.add(convertToICA("PM1", sensorInfo.pollutionData.pm1_0))

                                        individualAqis.maxOrNull()?.toInt() ?: sensorInfo.pollutionData.aqi.toInt()
                                    }
                                    "AQI: $aqi"
                                }
                            },
                            icon = BitmapDescriptorFactory.defaultMarker(markerColor),
                            zIndex = zIndex,
                            onClick = {
                                selectedMarker = deviceId
                                false
                            }
                        )
                    }
                }

                // Mostrar datos de contaminación cuando se selecciona un marcador
                selectedMarker?.let { markerId ->
                    sensorsData[markerId]?.let { sensorInfo ->
                        PollutionInfoCard(
                            sensorInfo = sensorInfo,
                            onDismiss = { selectedMarker = null },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(16.dp)
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun PollutionInfoCard(
        sensorInfo: SensorInfo,
        onDismiss: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        val data = sensorInfo.pollutionData

        // Para estaciones de Granada con datos WAQI O datos interpolados del usuario
        if (data.name.contains("Granada Norte") ||
            data.name.contains("Palacio de Congresos") ||
            data.name.contains("Estimación")
        ) {

            var showMoreInfo by remember { mutableStateOf(false) }

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

            // Colores según escala ICA española
            val aqiLevel = when {
                aqi <= 33 -> "Muy Buena"
                aqi <= 66 -> "Buena"
                aqi <= 100 -> "Regular"
                aqi <= 200 -> "Mala"
                else -> "Muy Mala"
            }
            val aqiColor = when {
                aqi <= 33 -> Color(0xFF00E5FF) // Cyan (Muy Buena)
                aqi <= 66 -> Color(0xFF4CAF50) // Verde (Buena)
                aqi <= 100 -> Color(0xFFFFC107) // Amarillo (Regular)
                aqi <= 200 -> Color(0xFFFF5722) // Rojo (Mala)
                else -> Color(0xFF9C27B0) // Púrpura (Muy Mala)
            }

            Card(
                modifier = modifier
                    .fillMaxWidth()
                    .animateContentSize(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color.White
                ),
                elevation = CardDefaults.cardElevation(8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    // Header con nombre y botón cerrar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = data.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Cerrar",
                                tint = Color.Gray
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(16.dp))

                    // Título de contaminantes
                    Text(
                        text = "Contaminantes atmosféricos",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = Color.Black.copy(alpha = 0.7f)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Lista de contaminantes con sus valores y colores
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Para datos interpolados del usuario
                        if (data.name.contains("Estimación")) {
                            ShowInterpolatedContaminants(data)
                        } else {
                            if (data.no2 > 0) {
                                val no2ICA = data.no2
                                ContaminantRow("NO₂", no2ICA, "", getICAColor(no2ICA))
                            }
                            if (data.so2 > 0) {
                                val so2ICA = data.so2
                                ContaminantRow("SO₂", so2ICA, "", getICAColor(so2ICA))
                            }
                            if (data.co > 0) {
                                val coICA = data.co
                                ContaminantRow("Monóxido de carbono", coICA, "", getICAColor(coICA))
                            }

                            // Para Palacio de Congresos, mostrar O3
                            if (data.name.contains("Palacio de Congresos")) {
                                val o3Value = data.o3
                                val o3ICA = o3Value
                                ContaminantRow("Ozono", o3ICA, "", getICAColor(o3ICA))
                            }

                            // Luego las partículas
                            if (data.pm10 > 0) {
                                val pm10ICA = data.pm10
                                ContaminantRow("PM10", pm10ICA, "", getICAColor(pm10ICA))
                            }
                            if (data.pm2_5 > 0) {
                                val pm25ICA = data.pm2_5
                                ContaminantRow("PM2.5", pm25ICA, "", getICAColor(pm25ICA))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Mostrar AQI
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = aqiColor.copy(alpha = 0.1f),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Calidad de Aire (ICA)",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = aqi.toString(),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = aqiColor
                            )
                            Text(
                                text = "($aqiLevel)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = aqiColor
                            )
                        }
                    }

                    // Botón "Más información"
                    Spacer(modifier = Modifier.height(8.dp))

                    TextButton(
                        onClick = { showMoreInfo = !showMoreInfo },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (showMoreInfo) "Menos información" else "Más información",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Icon(
                                imageVector = if (showMoreInfo) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Información adicional (temperatura, humedad, etc.)
                    AnimatedVisibility(visible = showMoreInfo) {
                        Column {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                            Text(
                                text = "Condiciones meteorológicas",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                color = Color.Black.copy(alpha = 0.7f),
                                modifier = Modifier.padding(bottom = 12.dp)
                            )

                            // Grid de condiciones meteorológicas
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    if (data.temperature > 0) {
                                        WeatherItem(
                                            "Temperatura",
                                            "${String.format("%.1f", data.temperature)}°C",
                                            Modifier.weight(1f)
                                        )
                                    }
                                    if (data.humidity > 0) {
                                        WeatherItem(
                                            "Humedad",
                                            "${String.format("%.0f", data.humidity)}%",
                                            Modifier.weight(1f)
                                        )
                                    }
                                }

                                // Datos adicionales según el tipo de estación
                                if (data.name.contains("Palacio de Congresos")) {
                                    WeatherItem(
                                        "Presión atmosférica",
                                        "1008.7 hPa",
                                        Modifier.fillMaxWidth()
                                    )
                                }

                                // Para datos interpolados, mostrar información sobre el cálculo
                                if (data.name.contains("Estimación")) {
                                    WeatherItem(
                                        "Método de cálculo",
                                        "Interpolación IDW",
                                        Modifier.fillMaxWidth()
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (data.name.contains("Estimación")) {
                                    "Fuente: Interpolación basada en estaciones cercanas - ${
                                        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                                            .format(Date(sensorInfo.lastUpdate))
                                    }"
                                } else {
                                    "Fuente: WAQI/Breathesafe - ${
                                        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                                            .format(Date(sensorInfo.lastUpdate))
                                    }"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray,
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            )
                        }
                    }
                }
            }
        } else {
            // Para sensores TTN (código original)
            val finalAqi =
                if (data.name.contains("my-tfg-2025") || data.name.contains("sensor-sinco2")) {
                    val individualAqis = mutableListOf<Double>()

                    if (data.co > 0) individualAqis.add(convertToICA("CO", data.co))
                    if (data.name.contains("my-tfg-2025") && data.co2 > 0) {
                        individualAqis.add(convertToICA("CO2", data.co2))
                    }
                    if (data.no2 > 0) individualAqis.add(convertToICA("NO2", data.no2))
                    if (data.pm10 > 0) individualAqis.add(convertToICA("PM10", data.pm10))
                    if (data.pm4_0 > 0) individualAqis.add(convertToICA("PM4", data.pm4_0))
                    if (data.pm2_5 > 0) individualAqis.add(convertToICA("PM25", data.pm2_5))
                    if (data.pm1_0 > 0) individualAqis.add(convertToICA("PM1", data.pm1_0))

                    individualAqis.maxOrNull()?.toInt() ?: data.aqi.toInt()
                } else {
                    data.aqi.toInt()
                }

            var showMoreInfo by remember { mutableStateOf(false) }

            val aqiLevel = when {
                finalAqi <= 33 -> "Muy Buena"
                finalAqi <= 66 -> "Buena"
                finalAqi <= 100 -> "Regular"
                finalAqi <= 200 -> "Mala"
                else -> "Muy Mala"
            }
            val aqiColor = when {
                finalAqi <= 33 -> Color(0xFF00E5FF)
                finalAqi <= 66 -> Color(0xFF4CAF50)
                finalAqi <= 100 -> Color(0xFFFFC107)
                finalAqi <= 200 -> Color(0xFFFF5722)
                else -> Color(0xFF9C27B0)
            }

            Card(
                modifier = modifier
                    .fillMaxWidth()
                    .animateContentSize(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color.White
                ),
                elevation = CardDefaults.cardElevation(8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = data.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Cerrar",
                                tint = Color.Gray
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Contaminantes atmosféricos",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = Color.Black.copy(alpha = 0.7f)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (data.co > 0) {
                            val coICA = convertToICA("CO", data.co)
                            ContaminantRow("CO", coICA, "", getICAColor(coICA))
                        }
                        if (data.name.contains("my-tfg-2025")) {
                            val co2ICA = convertToICA("CO2", data.co2)
                            ContaminantRow("CO₂", co2ICA, "", getICAColor(co2ICA))
                        }
                        if (data.no2 > 0) {
                            val no2ICA = convertToICA("NO2", data.no2)
                            ContaminantRow("NO₂", no2ICA, "", getICAColor(no2ICA))
                        }
                        if (data.pm10 > 0) {
                            val pm10ICA = convertToICA("PM10", data.pm10)
                            ContaminantRow("PM10", pm10ICA, "", getICAColor(pm10ICA))
                        }
                        if (data.pm4_0 > 0) {
                            val pm4ICA = convertToICA("PM4", data.pm4_0)
                            ContaminantRow("PM4", pm4ICA, "", getICAColor(pm4ICA))
                        }
                        if (data.pm2_5 > 0) {
                            val pm25ICA = convertToICA("PM25", data.pm2_5)
                            ContaminantRow("PM2.5", pm25ICA, "", getICAColor(pm25ICA))
                        }
                        if (data.pm1_0 > 0) {
                            val pm1ICA = convertToICA("PM1", data.pm1_0)
                            ContaminantRow("PM1", pm1ICA, "", getICAColor(pm1ICA))
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = aqiColor.copy(alpha = 0.1f),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Calidad de Aire (ICA)",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = finalAqi.toString(),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = aqiColor
                            )
                            Text(
                                text = "($aqiLevel)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = aqiColor
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    TextButton(
                        onClick = { showMoreInfo = !showMoreInfo },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (showMoreInfo) "Menos información" else "Más información",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Icon(
                                imageVector = if (showMoreInfo) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    AnimatedVisibility(visible = showMoreInfo) {
                        Column {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                            Text(
                                text = "Condiciones meteorológicas",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                color = Color.Black.copy(alpha = 0.7f),
                                modifier = Modifier.padding(bottom = 12.dp)
                            )

                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    if (data.temperature > 0) {
                                        WeatherItem(
                                            "Temperatura",
                                            "${String.format("%.1f", data.temperature)}°C",
                                            Modifier.weight(1f)
                                        )
                                    }
                                    if (data.humidity > 0) {
                                        WeatherItem(
                                            "Humedad",
                                            "${String.format("%.0f", data.humidity)}%",
                                            Modifier.weight(1f)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Fuente: TTN - ${
                                    SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                                        .format(Date(sensorInfo.lastUpdate))
                                }",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray,
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun ContaminantRow(
        name: String,
        value: Double,
        unit: String,
        color: Color
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(color)
                )
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
            }

            Text(
                text = "${String.format("%.0f", value)}$unit",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
    }

    @Composable
    fun WeatherItem(
        label: String,
        value: String,
        modifier: Modifier = Modifier
    ) {
        Column(
            modifier = modifier.padding(4.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }

    // Función para obtener color según valor ICA
    private fun getICAColor(ica: Double): Color = when {
        ica <= 33 -> Color(0xFF00E5FF) // Cyan - Muy Buena
        ica <= 66 -> Color(0xFF4CAF50) // Verde - Buena
        ica <= 100 -> Color(0xFFFFC107) // Amarillo - Regular
        ica <= 200 -> Color(0xFFFF5722) // Rojo - Mala
        else -> Color(0xFF9C27B0) // Púrpura - Muy Mala
    }

    // Función para convertir μg/m³ a ICA
    private fun convertToICA(pollutant: String, value: Double): Double {
        return when (pollutant.uppercase()) {
            "NO2" -> {
                when {
                    value <= 0.1 -> (value / 0.1) * 50
                    value <= 1.0 -> 50 + ((value - 0.1) / 0.9) * 50
                    value <= 2.5 -> 100 + ((value - 1.0) / 1.5) * 50
                    value <= 5.0 -> 150 + ((value - 2.5) / 2.5) * 50
                    value <= 10.0 -> 200 + ((value - 5.0) / 5.0) * 100
                    else -> 300 + ((value - 10.0) / 10.0) * 100
                }
            }

            "SO2" -> {
                when {
                    value <= 88 -> (value / 88) * 50
                    value <= 177 -> 50 + ((value - 88) / 89) * 50
                    value <= 443 -> 100 + ((value - 177) / 266) * 50
                    value <= 710 -> 150 + ((value - 443) / 267) * 50
                    value <= 887 -> 200 + ((value - 710) / 177) * 100
                    else -> 300 + ((value - 887) / 177) * 100
                }
            }

            "CO" -> {
                when {
                    value <= 50 -> (value / 50) * 50
                    value <= 200 -> 50 + ((value - 50) / 150) * 50
                    value <= 400 -> 100 + ((value - 200) / 200) * 50
                    value <= 800 -> 150 + ((value - 400) / 400) * 50
                    value <= 1200 -> 200 + ((value - 800) / 400) * 100
                    else -> 300 + ((value - 1200) / 800) * 100
                }
            }

            "PM10" -> {
                when {
                    value <= 50 -> (value / 50) * 50
                    value <= 100 -> 50 + ((value - 50) / 50) * 50
                    value <= 250 -> 100 + ((value - 100) / 150) * 50
                    value <= 350 -> 150 + ((value - 250) / 100) * 50
                    value <= 430 -> 200 + ((value - 350) / 80) * 100
                    else -> 300 + ((value - 430) / 80) * 100
                }
            }

            "PM25", "PM2_5" -> {
                when {
                    value <= 12 -> (value / 12) * 50
                    value <= 35 -> 50 + ((value - 12) / 23) * 50
                    value <= 55 -> 100 + ((value - 35) / 20) * 50
                    value <= 150 -> 150 + ((value - 55) / 95) * 50
                    value <= 250 -> 200 + ((value - 150) / 100) * 100
                    else -> 300 + ((value - 250) / 100) * 100
                }
            }

            "O3" -> {
                when {
                    value <= 60 -> (value / 60) * 50
                    value <= 120 -> 50 + ((value - 60) / 60) * 50
                    value <= 180 -> 100 + ((value - 120) / 60) * 50
                    value <= 240 -> 150 + ((value - 180) / 60) * 50
                    value <= 480 -> 200 + ((value - 240) / 240) * 100
                    else -> 300 + ((value - 480) / 120) * 100
                }
            }

            "PM1_0", "PM1" -> {
                when {
                    value <= 8 -> (value / 8) * 50
                    value <= 20 -> 50 + ((value - 8) / 12) * 50
                    value <= 35 -> 100 + ((value - 20) / 15) * 50
                    value <= 75 -> 150 + ((value - 35) / 40) * 50
                    value <= 150 -> 200 + ((value - 75) / 75) * 100
                    else -> 300 + ((value - 150) / 75) * 100
                }
            }

            "PM4_0", "PM4" -> {
                when {
                    value <= 25 -> (value / 25) * 50
                    value <= 50 -> 50 + ((value - 25) / 25) * 50
                    value <= 100 -> 100 + ((value - 50) / 50) * 50
                    value <= 200 -> 150 + ((value - 100) / 100) * 50
                    value <= 300 -> 200 + ((value - 200) / 100) * 100
                    else -> 300 + ((value - 300) / 100) * 100
                }
            }

            "CO2" -> {
                when {
                    value <= 400 -> (value / 400) * 33      // Aire exterior limpio - Muy Buena
                    value <= 1000 -> 33 + ((value - 400) / 600) * 33    // Interior bien ventilado - Buena
                    value <= 2000 -> 66 + ((value - 1000) / 1000) * 34  // Interior normal - Regular
                    value <= 5000 -> 100 + ((value - 2000) / 3000) * 100 // Interior mal ventilado - Mala
                    else -> 200 + ((value - 5000) / 5000) * 100         // Perjudicial - Muy Mala
                }
            }

            else -> {
                println("Contaminante no reconocido: $pollutant")
                value
            }
        }
    }

    // Función para mostrar contaminantes interpolados exactamente como aparecen en las estaciones originales
    @Composable
    fun ShowInterpolatedContaminants(data: PollutionData) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Los valores ya vienen interpolados en ICA, mostrarlos directamente

            // Gases primero
            if (data.co > 0) {
                ContaminantRow("CO", data.co, "", getICAColor(data.co))
            }
            if (data.co2 > 0) {
                ContaminantRow("CO₂", data.co2, "", getICAColor(data.co2))
            }
            if (data.no2 > 0) {
                ContaminantRow("NO₂", data.no2, "", getICAColor(data.no2))
            }
            if (data.so2 > 0) {
                ContaminantRow("SO₂", data.so2, "", getICAColor(data.so2))
            }
            if (data.o3 > 0) {
                ContaminantRow("Ozono", data.o3, "", getICAColor(data.o3))
            }

            // Partículas después
            if (data.pm10 > 0) {
                ContaminantRow("PM10", data.pm10, "", getICAColor(data.pm10))
            }
            if (data.pm4_0 > 0) {
                ContaminantRow("PM4", data.pm4_0, "", getICAColor(data.pm4_0))
            }
            if (data.pm2_5 > 0) {
                ContaminantRow("PM2.5", data.pm2_5, "", getICAColor(data.pm2_5))
            }
            if (data.pm1_0 > 0) {
                ContaminantRow("PM1", data.pm1_0, "", getICAColor(data.pm1_0))
            }
        }
    }

    @Composable
    fun StatusIndicator(isActive: Boolean) {
        val color = if (isActive) Color(0xFF4CAF50) else Color(0xFFFF9800)
        val text = if (isActive) "Conectado" else "Desconectado"

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = color
            )
        }
    }

    @Composable
    fun LocationInfo(
        icon: ImageVector,
        title: String,
        content: String,
        iconColor: Color = Color(0xFF2E7D32)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(24.dp)
            )

            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = Color.Gray
                )
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Normal
                )
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissions.toTypedArray(),
                REQUEST_LOCATION_PERMISSIONS
            )
        }
    }

    private fun startTTNService() {
        try {
            val intent = Intent(this, TTNDataService::class.java)
            startService(intent)
            Log.i(TAG, "Servicio TTN iniciado")
        } catch (e: Exception) {
            Log.e(TAG, "Error al iniciar servicio TTN", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun getCurrentLocation() {
        if (!hasLocationPermissions()) {
            return
        }

        fusedLocationClient.lastLocation
            .addOnSuccessListener { location ->
                if (location != null) {
                    myLocation = LatLng(location.latitude, location.longitude)
                    Log.i(TAG, "Ubicación del usuario: ${location.latitude}, ${location.longitude}")
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error obteniendo ubicación", e)
            }
    }

    private fun hasLocationPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }

    // Función para verificar si el servicio está ejecutándose
    private fun isAQIServiceRunning(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (AQIForegroundService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_LOCATION_PERMISSIONS -> {
                if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    Log.i(TAG, "Permisos de ubicación concedidos")
                    getCurrentLocationWithInterpolation()
                } else {
                    Log.w(TAG, "Permisos de ubicación denegados")
                }
            }

            REQUEST_NOTIFICATION_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.i(TAG, "Permisos de notificación concedidos")
                } else {
                    Log.w(TAG, "Permisos de notificación denegados")
                }
            }
        }
    }

    // Funciones para manejar notificaciones
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Notificaciones AQI"
            val descriptionText = "Notificaciones sobre la calidad del aire"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }

            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATION_PERMISSION
                )
            }
        }
    }

    private fun savePreferences() {
        sharedPreferences.edit().apply {
            putBoolean(PREF_NOTIFICATIONS_ENABLED, notificationsEnabled)
            putInt(PREF_NOTIFICATION_FREQUENCY, notificationFrequency)
            apply()
        }
    }




    // Manejar cuando la aplicación vuelve al primer plano
    override fun onResume() {
        super.onResume()

        // Verificar si el servicio sigue corriendo si las notificaciones están habilitadas
        if (notificationsEnabled && !isAQIServiceRunning()) {
            Log.w(TAG, "Servicio AQI no está corriendo, reiniciando...")
            NotificationScheduler.scheduleNotifications(this, notificationFrequency)
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        // Solo detener el servicio si las notificaciones están deshabilitadas
        // Si están habilitadas, el servicio debe continuar en segundo plano
        if (!notificationsEnabled) {
            NotificationScheduler.cancelNotifications(this)
        }
    }

    // Worker class para notificaciones (mantenido para compatibilidad)
    class AQINotificationWorker(
        context: Context,
        params: WorkerParameters
    ) : Worker(context, params) {

        override fun doWork(): Result {
            return try {
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

                    // Enviar notificación
                    sendNotification(aqi, aqiLevel)
                }

                Result.success()
            } catch (e: Exception) {
                Log.e("AQINotificationWorker", "Error enviando notificación", e)
                Result.failure()
            }
        }

        private fun sendNotification(aqi: Int, aqiLevel: String) {
            val intent = Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val pendingIntent: PendingIntent = PendingIntent.getActivity(
                applicationContext, 0, intent,
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

            val builder =
                NotificationCompat.Builder(applicationContext, MainActivity.NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info) // Icono temporal
                    .setContentTitle("$icon BreatheSafe")
                    .setContentText(notificationText)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(notificationText))
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .setWhen(System.currentTimeMillis())
                    .setShowWhen(true)

            with(NotificationManagerCompat.from(applicationContext)) {
                if (ActivityCompat.checkSelfPermission(
                        applicationContext,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    notify(MainActivity.NOTIFICATION_ID, builder.build())
                }
            }
        }
    }
}

// Data classes (mantener al final del archivo)
data class PollutionData(
    val name: String,
    val pm1_0: Double,
    val pm2_5: Double,
    val pm4_0: Double,
    val pm10: Double,
    val temperature: Double,
    val humidity: Double,
    val vocIndex: Double,
    val noxIndex: Double,
    val o3: Double,
    val co: Double,
    val aqi: Double,
    val co2: Double,
    val no2: Double,
    val so2: Double
)

data class TTNSensorData(
    val deviceId: String,
    val latitude: Double,
    val longitude: Double,
    val pm1_0: Double,
    val pm2_5: Double,
    val pm4_0: Double,
    val pm10: Double,
    val co: Double,
    val co2: Double,
    val no2: Double,
    val temperature: Double,
    val humidity: Double,
    val vocIndex: Double,
    val noxIndex: Double,
    val timestamp: Long
)

data class SensorInfo(
    val deviceId: String,
    val location: LatLng,
    val pollutionData: PollutionData,
    val lastUpdate: Long
)