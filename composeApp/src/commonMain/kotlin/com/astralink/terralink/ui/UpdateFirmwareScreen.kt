package com.astralink.terralink.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.astralink.terralink.model.Device

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateFirmwareScreen(
    device: Device,
    onBack: () -> Unit,
) {
    // Estado puramente UI para el mockup — no sube nada.
    var selectedFile by remember { mutableStateOf<String?>(null) }
    var uploading by remember { mutableStateOf(false) }
    val mockProgress = 0.42f

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Actualizar firmware", fontWeight = FontWeight.SemiBold)
                },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Atrás") }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            CurrentVersionCard(device = device)

            Spacer(Modifier.height(20.dp))

            Text(
                text = "Nuevo firmware",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))

            FileSelector(
                fileName = selectedFile,
                onSelect = { selectedFile = "savia-v0.2.0-aarch64.bin" },
                onClear = { selectedFile = null },
            )

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = { uploading = true },
                enabled = selectedFile != null && !uploading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (uploading) "Subiendo..." else "Subir e instalar")
            }

            if (uploading) {
                Spacer(Modifier.height(20.dp))
                UploadProgress(progress = mockProgress)
            }
        }
    }
}

@Composable
private fun CurrentVersionCard(device: Device) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Versión actual",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = device.firmwareVersion,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${device.name} · ${device.model}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FileSelector(
    fileName: String?,
    onSelect: () -> Unit,
    onClear: () -> Unit,
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (fileName == null) {
                Text(
                    text = "Ningún archivo seleccionado",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onSelect) {
                    Text("Seleccionar archivo .bin")
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = fileName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "16,2 MB · firmado Ed25519",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onClear) { Text("Cambiar") }
                }
            }
        }
    }
}

@Composable
private fun UploadProgress(progress: Float) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Subiendo vía BLE...",
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = "${(progress * 100).toInt()} %",
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(6.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Verificando firma del binario...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
