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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.astralink.terralink.ble.protocol.ConfigPatchMsg
import com.astralink.terralink.ble.protocol.ConfigSnapshotMsg
import com.astralink.terralink.ble.protocol.LoraStatus
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
import kotlinx.coroutines.launch

private sealed class ConnPhase {
    data object Loading : ConnPhase()
    data class Ready(val status: StatusMsg, val config: ConfigSnapshotMsg) : ConnPhase()
    data class Failed(val message: String) : ConnPhase()
}

// One communication module in the list (today only LoRa, from the config snapshot).
// `lora` carries the live link when a status is at hand; `consoleType` is null
// when the module has no command console (e.g. a future onboard-WiFi peripheral).
internal data class Peripheral(
    val type: String,
    val name: String,
    val secondary: String,
    val icon: ImageVector,
    val lora: LoraStatus?,
    val consoleType: String?,
)

/** Connectivity hub: one list of the station's communication modules, each row
 *  editable and removable. Adding (the FAB, shown while there is none) and editing
 *  both open [LoraPinsScreen]; removing confirms in place. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectivityScreen(
    station: SavedStation,
    active: ActiveSession,
    onOpenLoraConsole: () -> Unit,
    onEditLoraPins: () -> Unit,
    onBack: () -> Unit,
) {
    var phase by remember { mutableStateOf<ConnPhase>(ConnPhase.Loading) }
    var reloadKey by remember { mutableStateOf(0) }
    var confirmRemove by remember { mutableStateOf(false) }
    var showLoraPing by remember { mutableStateOf(false) }
    var loraOverride by remember { mutableStateOf<LoraStatus?>(null) }

    LaunchedEffect(reloadKey) {
        phase = ConnPhase.Loading
        phase = try {
            ConnPhase.Ready(active.readStatus(), active.readConfig())
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
            val ready = phase as? ConnPhase.Ready
            if (ready != null && !ready.config.lora) {
                FloatingActionButton(onClick = onEditLoraPins) {
                    Icon(TerraIcons.Add, contentDescription = "Añadir módulo")
                }
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
                        peripherals = listOfNotNull(loraPeripheral(p.config, effStatus)),
                        onOpenConsole = { type -> if (type == "lora") onOpenLoraConsole() },
                        onSignalClick = { per -> if (per.type == "lora") showLoraPing = true },
                        onEdit = { onEditLoraPins() },
                        onRemove = { confirmRemove = true },
                    )
                    if (confirmRemove) RemoveLoraDialog(
                        active = active, config = p.config,
                        onDismiss = { confirmRemove = false },
                        onSaved = { confirmRemove = false; loraOverride = null; reloadKey++ },
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

}

@Composable
private fun ConnReadyBody(
    peripherals: List<Peripheral>,
    onOpenConsole: (String) -> Unit,
    onSignalClick: (Peripheral) -> Unit,
    onEdit: (Peripheral) -> Unit,
    onRemove: (Peripheral) -> Unit,
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
                title = "Sin módulos",
                hint = "Añade el módulo LoRa con el botón +.",
            )
        } else {
            ListItemsCard(items = peripherals) { _, p ->
                PeripheralRow(
                    p,
                    onConsole = { p.consoleType?.let(onOpenConsole) },
                    onSignalClick = { onSignalClick(p) },
                    onEdit = { onEdit(p) },
                    onRemove = { onRemove(p) },
                )
            }
        }
        Spacer(Modifier.height(72.dp))   // keep content clear of the FAB
    }
}

/** One module: tapping the body opens its console (when it has one); the icons edit or remove it. */
@Composable
internal fun PeripheralRow(
    p: Peripheral,
    onConsole: () -> Unit,
    onSignalClick: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            p.icon, contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f).clickable(enabled = p.consoleType != null, onClick = onConsole)) {
            Text(p.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(p.secondary, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        p.lora?.let {
            LoraSignalIndicator(it, Modifier.size(26.dp).clickable(onClick = onSignalClick))
            Spacer(Modifier.width(4.dp))
        }
        IconButton(onClick = onEdit) {
            Icon(TerraIcons.Edit, contentDescription = "Editar", modifier = Modifier.size(20.dp))
        }
        IconButton(onClick = onRemove) {
            Icon(TerraIcons.Delete, contentDescription = "Quitar", modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.error)
        }
    }
}

/** The LoRa module as a list entry, or null when the config has it switched off.
 *  With a [status] at hand the row also shows the live link and opens the console. */
internal fun loraPeripheral(config: ConfigSnapshotMsg, status: StatusMsg? = null): Peripheral? {
    if (!config.lora) return null
    val pins = "GP${config.loraTx} · GP${config.loraRx}"
    val link = status?.lora
    return Peripheral(
        type = "lora",
        name = link?.module?.takeIf { it.isNotBlank() } ?: "LoRaWAN (Wio-E5)",
        secondary = if (link != null) loraVerdict(link) + " · " + pins else pins,
        icon = TerraIcons.Antenna,
        lora = link,
        consoleType = if (status != null) "lora" else null,
    )
}

private fun loraVerdict(l: LoraStatus): String = when {
    l.joined -> "Conectado" + (l.rssi?.let { " · $it dBm" } ?: "") +
        (l.lastMs?.let { " · ${formatRelativeMs(it)}" } ?: "")
    l.inited -> "Módulo OK · sin TTN"
    else -> "Sin respuesta"
}

// --- Removing the module (its pins are placed on LoraPinsScreen) ----------------

/** One config write behind a dialog's confirm button, with its busy flag and inline error. */
@Composable
private fun ConfigWriteDialog(
    active: ActiveSession,
    title: String,
    confirmText: String,
    confirmEnabled: Boolean,
    destructive: Boolean = false,
    patch: () -> ConfigPatchMsg,
    onDismiss: () -> Unit,
    onSaved: (ConfigSnapshotMsg) -> Unit,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    TerraDialog(
        onDismiss = onDismiss,
        title = title,
        confirmText = if (busy) "Guardando…" else confirmText,
        confirmEnabled = confirmEnabled && !busy,
        destructive = destructive,
        onConfirm = {
            busy = true; error = null
            scope.launch {
                try { onSaved(active.writeConfig(patch())) }
                catch (e: Throwable) { error = e.message ?: "No se pudo guardar" }
                finally { busy = false }
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            content()
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
internal fun RemoveLoraDialog(
    active: ActiveSession,
    config: ConfigSnapshotMsg,
    onDismiss: () -> Unit,
    onSaved: (ConfigSnapshotMsg) -> Unit,
) {
    ConfigWriteDialog(
        active = active,
        title = "Quitar módulo LoRa",
        confirmText = "Quitar",
        confirmEnabled = true,
        destructive = true,
        patch = { ConfigPatchMsg(lora = false) },
        onDismiss = onDismiss,
        onSaved = onSaved,
    ) {
        Text("La estación dejará de enviar datos y de recibir la hora por LoRa. " +
            "GP${config.loraTx} y GP${config.loraRx} quedan libres para sensores.")
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
