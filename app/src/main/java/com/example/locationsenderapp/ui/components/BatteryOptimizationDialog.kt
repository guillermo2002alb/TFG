// BatteryOptimizationDialog.kt
package com.example.locationsenderapp.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.locationsenderapp.utils.BatteryOptimizationHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatteryOptimizationDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var isOptimized by remember {
        mutableStateOf(!BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.BatteryAlert,
                    contentDescription = null,
                    tint = Color(0xFFFF6F00)
                )
                Text(
                    text = "Optimización de Batería",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (isOptimized) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFFF6F00).copy(alpha = 0.1f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(
                                text = "⚠️ Optimización de batería activa",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFFFF6F00)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Las notificaciones pueden no funcionar cuando la aplicación está cerrada.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                } else {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF4CAF50).copy(alpha = 0.1f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(
                                text = "✅ Optimización deshabilitada",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFF4CAF50)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Las notificaciones funcionarán correctamente en segundo plano.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Instrucciones específicas:",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        text = BatteryOptimizationHelper.getManufacturerSpecificInstructions(context),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        },
        confirmButton = {
            if (isOptimized) {
                TextButton(
                    onClick = {
                        BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(context)
                        onDismiss()
                    }
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Text("Configurar")
                    }
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("Entendido")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cerrar")
            }
        }
    )
}