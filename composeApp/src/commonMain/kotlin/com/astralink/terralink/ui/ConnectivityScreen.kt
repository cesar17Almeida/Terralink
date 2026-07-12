package com.astralink.terralink.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.astralink.terralink.ble.protocol.LoraStatus
import com.astralink.terralink.ble.protocol.PinmapMsg
import com.astralink.terralink.ble.protocol.StatusMsg
import com.astralink.terralink.ble.session.ActiveSession
import com.astralink.terralink.model.SavedStation
import com.astralink.terralink.ui.components.BackIconButton
import com.astralink.terralink.ui.components.EmptyState
import com.astralink.terralink.ui.components.ListItemsCard
import com.astralink.terralink.ui.components.LoraPingDialog
import com.astralink.terralink.ui.components.LoraSignalIndicator
import com.astralink.terralink.ui.components.TerraDialog
import com.astralink.terralink.ui.components.TerraIcons
import com.astralink.terralink.util.formatRelativeMs

private sealed class ConnPhase {
    data object Loading : ConnPhase()
    data class Ready(val status: StatusMsg, val pinmap: PinmapMsg?) : ConnPhase()
    data class Failed(val message: String) : ConnPhase()
}

// One communication module in the list (Phase 1: derived from StatusMsg; a real
// peripherals[] table comes in Phase 2). `consoleType` is null when the module
// has no command console (e.g. a future onboard-WiFi peripheral).
private data class Peripheral(
    val type: String,
    val name: String,
    val secondary: String,
    val icon: ImageVector,
    val lora: LoraStatus?,
    val consoleType: String?,
)

/** Connectivity hub: one list of the station's communication modules, each row
 *  with its own console icon. A FAB adds a peripheral. The pin map lives on Home.
 *  Phase 1 derives the list from StatusMsg (today only LoRa). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectivityScreen(
    station: SavedStation,
    active: ActiveSession,
    onOpenLoraConsole: () -> Unit,
    onBack: () -> Unit,
) {
    var phase by remember { mutableStateOf<ConnPhase>(ConnPhase.Loading) }
    var reloadKey by remember { mutableStateOf(0) }
    var showAddPeripheral by remember { mutableStateOf(false) }
    var showLoraPing by remember { mutableStateOf(false) }
    var loraOverride by remember { mutableStateOf<LoraStatus?>(null) }

    LaunchedEffect(reloadKey) {
        phase = ConnPhase.Loading
        phase = try {
            val status = active.readStatus()
            val pinmap = runCatching { active.readPinmap() }.getOrNull()
            ConnPhase.Ready(status, pinmap)
        } catch (e: Throwable) {
            ConnPhase.Failed(e.message ?: "No se pudo leer la conectividad")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Conectividad", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { BackIconButton(onClick = onBack) },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddPeripheral = true }) {
                Icon(TerraIcons.Add, contentDescription = "Añadir periférico")
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (val p = phase) {
                ConnPhase.Loading -> ConnCentered("Leyendo conectividad…")
                is ConnPhase.Failed -> ConnError(p.message, onRetry = { reloadKey++ })
                is ConnPhase.Ready -> {
                    val effStatus = loraOverride?.let { p.status.copy(lora = it) } ?: p.status
                    ConnReadyBody(
                        peripherals = buildPeripherals(effStatus, p.pinmap),
                        onOpenConsole = { type -> if (type == "lora") onOpenLoraConsole() },
                        onSignalClick = { per -> if (per.type == "lora") showLoraPing = true },
                    )
                    if (showLoraPing) {
                        LoraPingDialog(
                            session = active,
                            bleId = station.bleId,
                            initial = effStatus.lora,
                            onDismiss = { showLoraPing = false },
                            onResult = { loraOverride = it },
                        )
                    }
                }
            }
        }
    }

    if (showAddPeripheral) {
        AddPeripheralDialog(onDismiss = { showAddPeripheral = false })
    }
}

@Composable
private fun ConnReadyBody(
    peripherals: List<Peripheral>,
    onOpenConsole: (String) -> Unit,
    onSignalClick: (Peripheral) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        if (peripherals.isEmpty()) {
            EmptyState(
                icon = TerraIcons.Antenna,
                title = "Sin periféricos",
                hint = "Esta estación no reporta módulos de comunicación.",
            )
        } else {
            ListItemsCard(items = peripherals) { _, p ->
                PeripheralRow(
                    p,
                    onConsole = { p.consoleType?.let(onOpenConsole) },
                    onSignalClick = { onSignalClick(p) },
                )
            }
        }
        Spacer(Modifier.height(72.dp))   // keep content clear of the FAB
    }
}

@Composable
private fun PeripheralRow(p: Peripheral, onConsole: () -> Unit, onSignalClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            p.icon, contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(p.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(p.secondary, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        p.lora?.let {
            LoraSignalIndicator(it, Modifier.size(26.dp).clickable(onClick = onSignalClick))
            Spacer(Modifier.width(4.dp))
        }
        if (p.consoleType != null) {
            IconButton(onClick = onConsole) {
                Icon(TerraIcons.Terminal, contentDescription = "Abrir consola")
            }
        }
    }
}

// --- Phase 1 peripheral derivation -----------------------------------------

private fun buildPeripherals(status: StatusMsg, pinmap: PinmapMsg?): List<Peripheral> = buildList {
    status.lora?.let { l ->
        val pins = pinmap.loraPins()
        val secondary = loraVerdict(l) +
            if (pins.isNotEmpty()) " · " + pins.joinToString(", ") { "GP$it" } else ""
        add(
            Peripheral(
                type = "lora",
                name = l.module.ifBlank { "LoRaWAN (Wio-E5)" },
                secondary = secondary,
                icon = TerraIcons.Antenna,
                lora = l,
                consoleType = "lora",
            ),
        )
    }
}

private fun loraVerdict(l: LoraStatus): String = when {
    l.joined -> "Conectado" + (l.rssi?.let { " · $it dBm" } ?: "") +
        (l.lastMs?.let { " · ${formatRelativeMs(it)}" } ?: "")
    l.inited -> "Módulo OK · sin TTN"
    else -> "Sin respuesta"
}

/** GPIOs the firmware reports as taken by the LoRa UART (reason = "lora_uart"). */
private fun PinmapMsg?.loraPins(): List<Int> =
    this?.pins?.filter { it.reason == "lora_uart" }?.map { it.gpio }?.sorted() ?: emptyList()

@Composable
private fun AddPeripheralDialog(onDismiss: () -> Unit) {
    TerraDialog(
        onDismiss = onDismiss,
        title = "Añadir periférico",
        confirmText = "Entendido",
        onConfirm = onDismiss,
        dismissText = null,
    ) {
        Text(
            "Hoy el único periférico es el módulo LoRaWAN (Wio-E5), que se habilita y se " +
                "conecta a sus pines TX/RX desde el firmware. Añadir y configurar periféricos " +
                "(otro LoRa, WiFi…) desde la app llegará próximamente.",
        )
    }
}

@Composable
private fun ConnCentered(text: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ConnError(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("No se pudo cargar", style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRetry) { Text("Reintentar") }
    }
}
