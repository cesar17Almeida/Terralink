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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.astralink.terralink.ble.session.ActiveSession
import com.astralink.terralink.ble.session.BlobProgress
import com.astralink.terralink.model.SavedStation
import kotlinx.coroutines.flow.catch

// Captures "0.1.0", "0.1.0-rc1", "1.2", optionally prefixed with "v".
private val VERSION_REGEX = Regex("""v?(\d+\.\d+(?:\.\d+)?(?:-[\w.]+)?)""")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateFirmwareScreen(
    station: SavedStation,
    active: ActiveSession,
    onBack: () -> Unit,
) {
    var firmware by remember { mutableStateOf<PickedFile?>(null) }
    var version by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<BlobProgress?>(null) }
    var uploading by remember { mutableStateOf(false) }

    val launchPicker = rememberFirmwareFilePicker { picked ->
        firmware = picked
        // Pre-fill version if the filename embeds one; user can still override.
        version = VERSION_REGEX.find(picked.name)?.groupValues?.get(1) ?: ""
    }

    val terminal = progress is BlobProgress.Success || progress is BlobProgress.Failure
    val canUpload = firmware != null && version.isNotBlank() && !uploading

    LaunchedEffect(uploading) {
        if (!uploading) return@LaunchedEffect
        val bytes = firmware?.bytes ?: return@LaunchedEffect
        active.pushFirmware(bytes, version)
            .catch { e ->
                progress = BlobProgress.Failure(e.message ?: e::class.simpleName ?: "upload failed")
            }
            .collect { p -> progress = p }
    }

    LaunchedEffect(progress) {
        // Re-enable the button once we've reached a terminal state.
        if (progress is BlobProgress.Success || progress is BlobProgress.Failure) {
            uploading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Actualizar firmware", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { TextButton(onClick = onBack) { Text("Atrás") } },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            TargetStationCard(station = station)

            Spacer(Modifier.height(20.dp))

            Text(
                text = "Nuevo firmware",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))

            FileSelector(
                file = firmware,
                onSelect = launchPicker,
                onClear = {
                    firmware = null
                    version = ""
                    progress = null
                },
            )

            if (firmware != null) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = version,
                    onValueChange = { version = it },
                    label = { Text("Versión") },
                    placeholder = { Text("ej. 0.2.0") },
                    singleLine = true,
                    enabled = !uploading,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = {
                    progress = null
                    uploading = true
                },
                enabled = canUpload,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (uploading) "Subiendo..." else "Subir e instalar")
            }

            progress?.let {
                Spacer(Modifier.height(20.dp))
                UploadProgress(progress = it)
            }
        }
    }
}

@Composable
private fun TargetStationCard(station: SavedStation) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Estación destino",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = station.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = station.bleId,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FileSelector(
    file: PickedFile?,
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
            if (file == null) {
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
                            text = file.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = formatBytes(file.bytes.size),
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
private fun UploadProgress(progress: BlobProgress) {
    val (label, fraction) = when (progress) {
        BlobProgress.Starting -> "Iniciando..." to 0f
        BlobProgress.WaitingForPsm -> "Esperando canal L2CAP..." to 0f
        is BlobProgress.Transferring -> "Subiendo vía L2CAP..." to progress.fraction
        BlobProgress.Verifying -> "Verificando firma y aplicando..." to 1f
        BlobProgress.Success -> "Instalación completada" to 1f
        is BlobProgress.Failure -> "Error: ${progress.reason}" to 0f
    }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = label, style = MaterialTheme.typography.labelMedium)
            if (progress is BlobProgress.Transferring) {
                Text(
                    text = "${(progress.fraction * 100).toInt()} %",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(6.dp),
        )
    }
}

private fun formatBytes(n: Int): String {
    if (n < 1024) return "$n B"
    if (n < 1024 * 1024) return "${n / 1024} KB"
    val mb = n / 1024.0 / 1024.0
    return "${(mb * 10).toInt() / 10.0} MB"
}
