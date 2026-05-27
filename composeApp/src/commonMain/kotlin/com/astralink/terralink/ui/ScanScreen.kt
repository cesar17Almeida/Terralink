package com.astralink.terralink.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.astralink.terralink.ble.ScannedDevice
import com.astralink.terralink.ble.session.SaviaSession
import com.astralink.terralink.ui.components.BackIconButton
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    session: SaviaSession,
    onBack: () -> Unit,
    onPair: (ScannedDevice) -> Unit,
) {
    var showAll by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }
    // Keep the latest entry per device id so the list doesn't grow unbounded
    // and we always show the freshest RSSI/name for each peer.
    val results: SnapshotStateMap<String, ScannedDevice> = remember { mutableStateMapOf() }

    LaunchedEffect(showAll) {
        results.clear()
        scanError = null
        session.scan(saviaOnly = !showAll)
            .catch { e -> scanError = e.message ?: e::class.simpleName ?: "scan failed" }
            .collect { d -> results[d.id] = d }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Buscar estaciones", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    BackIconButton(onClick = onBack)
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            ShowAllToggleRow(showAll = showAll, onChange = { showAll = it })
            Spacer(Modifier.height(8.dp))

            if (scanError != null) {
                ScanErrorBanner(message = scanError!!)
                Spacer(Modifier.height(8.dp))
            }

            ScanResultsList(
                // Savia stations always render first; inside each group we
                // sort by signal strength (closer = higher RSSI = earlier).
                devices = results.values.sortedWith(
                    compareByDescending<ScannedDevice> { it.isSavia }
                        .thenByDescending { it.rssi },
                ),
                onPick = onPair,
            )
        }
    }
}

@Composable
private fun ShowAllToggleRow(showAll: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Mostrar todos",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = if (showAll) "Mostrando todos los dispositivos BLE."
                       else "Solo estaciones Savia.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = showAll, onCheckedChange = onChange)
    }
}

@Composable
private fun ScanErrorBanner(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Text(
            modifier = Modifier.padding(12.dp),
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun ScanResultsList(devices: List<ScannedDevice>, onPick: (ScannedDevice) -> Unit) {
    if (devices.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "Buscando...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(devices, key = { it.id }) { device ->
            DeviceRow(device = device, onPick = { onPick(device) })
        }
    }
}

@Composable
private fun DeviceRow(device: ScannedDevice, onPick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (device.isSavia) it.clickable(onClick = onPick) else it },
        colors = if (device.isSavia) CardDefaults.cardColors()
                 else CardDefaults.cardColors(
                     containerColor = MaterialTheme.colorScheme.surfaceVariant,
                 ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (device.isSavia) 1.dp else 0.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name ?: "Dispositivo sin nombre",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (device.isSavia) Color.Unspecified
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${device.id}  ·  ${device.rssi} dBm",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DeviceBadge(isSavia = device.isSavia)
        }
    }
}

@Composable
private fun DeviceBadge(isSavia: Boolean) {
    AssistChip(
        onClick = {},
        enabled = false,
        label = {
            Text(
                text = if (isSavia) "Savia" else "No compatible",
                style = MaterialTheme.typography.labelSmall,
            )
        },
        colors = AssistChipDefaults.assistChipColors(
            disabledContainerColor = if (isSavia)
                MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface,
            disabledLabelColor = if (isSavia)
                MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}
