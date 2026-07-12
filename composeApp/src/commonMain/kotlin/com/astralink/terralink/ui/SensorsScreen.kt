package com.astralink.terralink.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.astralink.terralink.ble.protocol.ConfigPatchMsg
import com.astralink.terralink.ble.protocol.PinmapMsg
import com.astralink.terralink.ble.protocol.SensorInfo
import com.astralink.terralink.ble.protocol.SensorPatch
import com.astralink.terralink.ble.protocol.SensorType
import com.astralink.terralink.ble.session.ActiveSession
import com.astralink.terralink.model.SavedStation
import com.astralink.terralink.ui.components.BackIconButton
import com.astralink.terralink.ui.components.EmptyState
import com.astralink.terralink.ui.components.ListItemsCard
import com.astralink.terralink.ui.components.TerraDialog
import com.astralink.terralink.ui.components.TerraIcons
import kotlinx.coroutines.launch

private sealed class SensorsPhase {
    data object Loading : SensorsPhase()
    data class Ready(val sensors: List<SensorInfo>) : SensorsPhase()
    data class Failed(val message: String) : SensorsPhase()
}

/** Dedicated sensors hub: one list of the station's configured sensors, a terminal
 *  icon to open the probe console, and a FAB that opens the create/edit wizard.
 *  Sensors live on the device; we read the config snapshot and pinmap. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SensorsScreen(
    station: SavedStation,
    active: ActiveSession,
    onOpenConsole: () -> Unit,
    onBack: () -> Unit,
    openWizardOnStart: Boolean = false,
) {
    var phase by remember { mutableStateOf<SensorsPhase>(SensorsPhase.Loading) }
    var reloadKey by remember { mutableStateOf(0) }
    var pinmap by remember { mutableStateOf<PinmapMsg?>(null) }

    LaunchedEffect(reloadKey) {
        phase = SensorsPhase.Loading
        phase = try {
            val cfg = active.readConfig()
            pinmap = runCatching { active.readPinmap() }.getOrNull()
            SensorsPhase.Ready(cfg.sensors)
        } catch (e: Throwable) {
            SensorsPhase.Failed(e.message ?: "No se pudieron leer los sensores")
        }
    }

    when (val p = phase) {
        SensorsPhase.Loading -> SensorsScaffold(onBack, onOpenConsole, consoleEnabled = false) {
            SensorsCentered("Leyendo sensores…")
        }
        is SensorsPhase.Failed -> SensorsScaffold(onBack, onOpenConsole, consoleEnabled = false) {
            SensorsError(p.message, onRetry = { reloadKey++ })
        }
        is SensorsPhase.Ready -> SensorsReady(
            station = station, sensors = p.sensors, pinmap = pinmap, active = active,
            openWizardOnStart = openWizardOnStart,
            onOpenConsole = onOpenConsole, onBack = onBack, onReload = { reloadKey++ },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SensorsScaffold(
    onBack: () -> Unit,
    onOpenConsole: () -> Unit,
    consoleEnabled: Boolean,
    fab: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sensores", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { BackIconButton(onClick = onBack) },
                actions = {
                    IconButton(onClick = onOpenConsole, enabled = consoleEnabled) {
                        Icon(TerraIcons.Terminal, contentDescription = "Consola de sensor")
                    }
                },
            )
        },
        floatingActionButton = fab,
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) { content() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SensorsReady(
    station: SavedStation,
    sensors: List<SensorInfo>,
    pinmap: PinmapMsg?,
    active: ActiveSession,
    openWizardOnStart: Boolean,
    onOpenConsole: () -> Unit,
    onBack: () -> Unit,
    onReload: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Open the "add sensor" wizard immediately when arriving here to add one (e.g. from
    // the SDI-12 console's empty state).
    var wizard by remember { mutableStateOf<SensorTarget?>(if (openWizardOnStart) SensorTarget.New else null) }
    var historyTarget by remember { mutableStateOf<SensorInfo?>(null) }
    var savingSensors by remember { mutableStateOf(false) }
    var sensorError by remember { mutableStateOf<String?>(null) }
    var confirmDeleteSensor by remember { mutableStateOf<Int?>(null) }

    // Live actuator states (StatusMsg.act), refreshed after each toggle.
    var actStates by remember { mutableStateOf<List<com.astralink.terralink.ble.protocol.ActuatorState>>(emptyList()) }
    var togglingActuator by remember { mutableStateOf(false) }
    LaunchedEffect(sensors) {
        if (sensors.any { it.type == SensorType.ACTUATOR }) {
            actStates = runCatching { active.readStatus().act }.getOrNull().orEmpty()
        }
    }

    fun toggleActuator(port: Int, on: Boolean) {
        togglingActuator = true
        sensorError = null
        scope.launch {
            try {
                active.setActuator(port, on)
                actStates = runCatching { active.readStatus().act }.getOrNull().orEmpty()
            } catch (e: Throwable) {
                sensorError = e.message ?: "No se pudo cambiar el actuador"
            } finally {
                togglingActuator = false
            }
        }
    }

    // The firmware swaps the whole sensor table, so every change re-sends the full list.
    fun sendSensorTable(table: List<SensorPatch>, onOk: () -> Unit) {
        savingSensors = true
        sensorError = null
        scope.launch {
            try {
                active.writeConfig(ConfigPatchMsg(sensors = table))
                onOk()
            } catch (e: Throwable) {
                sensorError = e.message ?: "No se pudo guardar el sensor"
            } finally {
                savingSensors = false
            }
        }
    }

    // The wizard takes over the whole screen while open (its own Scaffold).
    val wizardTarget = wizard
    if (wizardTarget != null) {
        SensorWizardScreen(
            target = wizardTarget,
            existing = sensors,
            pinmap = pinmap,
            busy = savingSensors,
            error = sensorError,
            onCancel = { wizard = null; sensorError = null },
            onSave = { table -> sendSensorTable(table) { wizard = null; sensorError = null; onReload() } },
        )
        return
    }

    // Tapping a sensor opens its history (full screen with its own Scaffold).
    val history = historyTarget
    if (history != null) {
        SensorHistoryScreen(
            station = station,
            active = active,
            sensor = history,
            onBack = { historyTarget = null },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sensores", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { BackIconButton(onClick = onBack) },
                actions = {
                    IconButton(onClick = onOpenConsole) {
                        Icon(TerraIcons.Terminal, contentDescription = "Consola de sensor")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { sensorError = null; wizard = SensorTarget.New }) {
                Icon(TerraIcons.Add, contentDescription = "Añadir sensor")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            if (sensors.isEmpty()) {
                EmptyState(
                    icon = TerraIcons.Sensors,
                    title = "No hay sensores configurados",
                    hint = "Usa el botón + para añadir el primero.",
                )
            } else {
                ListItemsCard(items = sensors) { i, s ->
                    if (s.type == SensorType.ACTUATOR) {
                        ActuatorRow(
                            sensor = s,
                            on = actStates.firstOrNull { it.port == s.port }?.on ?: false,
                            busy = savingSensors || togglingActuator,
                            onToggle = { on -> toggleActuator(s.port, on) },
                            onEdit = { sensorError = null; wizard = SensorTarget.Edit(i, sensors[i]) },
                            onDelete = { confirmDeleteSensor = i },
                        )
                    } else {
                        SensorRow(index = i, sensor = s, busy = savingSensors,
                            onOpen = { historyTarget = s },
                            onEdit = { sensorError = null; wizard = SensorTarget.Edit(i, sensors[i]) },
                            onDelete = { confirmDeleteSensor = i })
                    }
                }
            }
            sensorError?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(80.dp))   // keep the last row clear of the FAB
        }
    }

    confirmDeleteSensor?.let { idx ->
        val s = sensors.getOrNull(idx)
        TerraDialog(
            onDismiss = { confirmDeleteSensor = null },
            title = "¿Eliminar sensor?",
            confirmText = "Eliminar",
            destructive = true,
            confirmEnabled = !savingSensors,
            onConfirm = {
                confirmDeleteSensor = null
                sendSensorTable(sensorTableWithout(sensors, idx)) {
                    sensorError = null; onReload()
                }
            },
        ) {
            Text(
                "Se quitará el sensor del puerto ${s?.port ?: (idx + 1)}" +
                    (s?.let { " (${sensorTypeLabel(it.type)})" } ?: "") +
                    ". Puedes volver a añadirlo cuando quieras.",
            )
        }
    }
}

/** Sensor-list row for a digital actuator: name/pin on the left, a live ON/OFF Switch,
 *  and the same edit/delete affordances as an ordinary sensor. */
@Composable
private fun ActuatorRow(
    sensor: SensorInfo,
    on: Boolean,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Actuador · GPIO ${sensor.gpio}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "Puerto ${sensor.port} · ${if (on) "encendido" else "apagado"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = on, onCheckedChange = onToggle, enabled = !busy)
        IconButton(onClick = onEdit, enabled = !busy) {
            Icon(TerraIcons.Edit, contentDescription = "Editar", modifier = Modifier.size(20.dp))
        }
        IconButton(onClick = onDelete, enabled = !busy) {
            Icon(TerraIcons.Delete, contentDescription = "Eliminar", modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun SensorsCentered(text: String) {
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
private fun SensorsError(message: String, onRetry: () -> Unit) {
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
