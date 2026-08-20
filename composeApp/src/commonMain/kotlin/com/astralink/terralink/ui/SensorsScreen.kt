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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.astralink.terralink.ble.protocol.ConfigPatchMsg
import com.astralink.terralink.ble.protocol.PinmapMsg
import com.astralink.terralink.ble.protocol.SensorInfo
import com.astralink.terralink.ble.protocol.SensorPatch
import com.astralink.terralink.ble.protocol.SensorType
import com.astralink.terralink.ble.session.ActiveSession
import com.astralink.terralink.model.SavedStation
import com.astralink.terralink.state.ReadingsRepository
import com.astralink.terralink.ui.components.BackIconButton
import com.astralink.terralink.ui.components.EmptyState
import com.astralink.terralink.ui.components.ListItemsCard
import com.astralink.terralink.ui.components.SectionHeader
import com.astralink.terralink.ui.components.TerraDialog
import com.astralink.terralink.ui.components.parseSdi12Address
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
    var pinmapWarning by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reloadKey) {
        phase = SensorsPhase.Loading
        phase = try {
            val cfg = active.readConfig()
            // The pin inventory is not required to LIST sensors, so its failure
            // doesn't sink the screen -- but it must not pass unnoticed either: with
            // no inventory the pinout paints every pin free.
            try {
                pinmap = active.readPinmap()
                pinmapWarning = null
            } catch (e: Throwable) {
                pinmap = null
                pinmapWarning = buildString {
                    append("No se pudo leer el mapa de pines de la estación")
                    e.message?.let { append(" ($it)") }
                    append(". Se mostrarán todos los pines como libres: si eliges uno ")
                    append("ocupado o reservado, la estación rechazará el sensor al guardar.")
                }
            }
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
            station = station, sensors = p.sensors, pinmap = pinmap,
            pinmapWarning = pinmapWarning, active = active,
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
                title = { Text("Sensores y salidas", fontWeight = FontWeight.SemiBold) },
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
    pinmapWarning: String?,
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
    var wizard by remember { mutableStateOf<SensorTarget?>(if (openWizardOnStart) SensorTarget.New() else null) }
    var historyTarget by remember { mutableStateOf<SensorInfo?>(null) }
    var savingSensors by remember { mutableStateOf(false) }
    var sensorError by remember { mutableStateOf<String?>(null) }
    var confirmDeleteSensor by remember { mutableStateOf<Int?>(null) }
    var dropHistory by remember { mutableStateOf(false) }

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
                // Returns the station's CONFIRMED slot states (it polls until the
                // supervisor has actually driven the pin), so a valve that never
                // moved can't render as switched.
                actStates = active.setActuator(port, on)
            } catch (e: Throwable) {
                val msg = e.message ?: "No se pudo cambiar el actuador"
                sensorError = msg
                scope.launch { snackbarHostState.showSnackbar(msg) }
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
                // Carries the station's own reason ("Sensor 2: ese pin ya lo usa otro
                // sensor"), so show it verbatim -- and in the snackbar too, since the
                // delete path has no wizard open to host the inline note.
                val msg = e.message ?: "No se pudo guardar el sensor"
                sensorError = msg
                // Detached: showSnackbar suspends until the snackbar is dismissed, and
                // while the wizard is open its host isn't composed -- awaiting it here
                // would leave the screen stuck in "guardando".
                scope.launch { snackbarHostState.showSnackbar(msg) }
            } finally {
                savingSensors = false
            }
        }
    }

    // Wipe one sensor's readings in both copies. The station's ring only holds ~48 h,
    // but that tail would still reach a future sensor on the same port, so it goes too.
    // A station-side failure is reported, not swallowed: the local delete already
    // happened and the user must know the two copies disagree.
    fun deleteSensorHistory(port: Int) {
        runCatching { ReadingsRepository.deleteByStationPort(station.bleId, port) }
            .onFailure { e ->
                val msg = e.message ?: "No se pudo borrar el histórico local"
                sensorError = msg
                scope.launch { snackbarHostState.showSnackbar(msg) }
            }
        scope.launch {
            try {
                active.clearPort(port)
            } catch (e: Throwable) {
                val msg = "El histórico se borró en el móvil, pero la estación no pudo " +
                    "borrar su copia (${e.message ?: "sin detalle"})."
                sensorError = msg
                snackbarHostState.showSnackbar(msg)
            }
        }
    }

    // Ask the probe on `gpio` for its own SDI-12 address (`?!`) so the wizard does not
    // have to ask the installer. remember'd: an inline lambda would be a new instance
    // on every recomposition and restart the probe. Failures answer null -- the field
    // retries and then falls back to the factory default.
    val probeSdi12Address: suspend (Int) -> String? = remember(active) {
        { gpio -> parseSdi12Address(active.sdi12Command(gpio, "?!").lines) }
    }

    // The wizard takes over the whole screen while open (its own Scaffold).
    val wizardTarget = wizard
    if (wizardTarget != null) {
        SensorWizardScreen(
            target = wizardTarget,
            existing = sensors,
            pinmap = pinmap,
            pinmapWarning = pinmapWarning,
            busy = savingSensors,
            error = sensorError,
            onCancel = { wizard = null; sensorError = null },
            onSave = { table -> sendSensorTable(table) { wizard = null; sensorError = null; onReload() } },
            onProbeSdi12Address = probeSdi12Address,
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
                title = { Text("Sensores y salidas", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { BackIconButton(onClick = onBack) },
                actions = {
                    IconButton(onClick = onOpenConsole) {
                        Icon(TerraIcons.Terminal, contentDescription = "Consola de sensor")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { sensorError = null; wizard = SensorTarget.New() }) {
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
            // Surfaced here too, not only inside the wizard: it changes what the pin
            // picker is showing, so the user should know before opening it.
            pinmapWarning?.let {
                Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onReload) { Text("Reintentar") }
                Spacer(Modifier.height(4.dp))
            }
            if (sensors.isEmpty()) {
                EmptyState(
                    icon = TerraIcons.Sensors,
                    title = "Nada configurado todavía",
                    hint = "Usa el botón + para añadir el primer sensor o actuador.",
                )
            } else {
                // Inputs and outputs are different things and are listed as such. Both
                // draw from the same pool of slots, so the row keeps its index into
                // `sensors` -- that index is what edit/delete address.
                val (outputs, inputs) = sensors.withIndex().partition { it.value.type == SensorType.ACTUATOR }
                ListSection("Entradas", "Miden y guardan lecturas")
                if (inputs.isEmpty()) {
                    ListSectionEmpty("Ningún sensor configurado.")
                } else {
                    ListItemsCard(items = inputs) { _, (i, s) ->
                        SensorRow(index = i, sensor = s, busy = savingSensors,
                            onOpen = { historyTarget = s },
                            onEdit = { sensorError = null; wizard = SensorTarget.Edit(i, sensors[i]) },
                            onDelete = { confirmDeleteSensor = i })
                    }
                }
                Spacer(Modifier.height(20.dp))
                ListSection(
                    "Salidas", "Se accionan desde aquí; no generan lecturas",
                    accent = MaterialTheme.colorScheme.tertiary,
                    action = "Añadir",
                    onAction = { sensorError = null; wizard = SensorTarget.New(SensorType.ACTUATOR) },
                    actionEnabled = !savingSensors,
                )
                if (outputs.isEmpty()) {
                    ListSectionEmpty("Ninguna salida configurada.")
                } else {
                    ListItemsCard(items = outputs) { _, (i, s) ->
                        ActuatorRow(
                            sensor = s,
                            on = actStates.firstOrNull { it.port == s.port }?.on ?: false,
                            busy = savingSensors || togglingActuator,
                            onToggle = { on -> toggleActuator(s.port, on) },
                            onEdit = { sensorError = null; wizard = SensorTarget.Edit(i, sensors[i]) },
                            onDelete = { confirmDeleteSensor = i },
                        )
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
        val port = s?.port
        val isOutput = s?.type == SensorType.ACTUATOR
        // What the installer stands to lose, counted before they decide. An output
        // has no readings of its own, so it never asks about a history.
        val cached = remember(port) {
            port?.let { runCatching { ReadingsRepository.countByStationPort(station.bleId, it) }
                .getOrDefault(0L) } ?: 0L
        }
        TerraDialog(
            onDismiss = { confirmDeleteSensor = null; dropHistory = false },
            title = if (isOutput) "¿Eliminar actuador?" else "¿Eliminar sensor?",
            confirmText = "Eliminar",
            destructive = true,
            confirmEnabled = !savingSensors,
            onConfirm = {
                confirmDeleteSensor = null
                val drop = dropHistory
                dropHistory = false
                sendSensorTable(sensorTableWithout(sensors, idx)) {
                    sensorError = null
                    if (drop && port != null) deleteSensorHistory(port)
                    onReload()
                }
            },
        ) {
            Text(
                "Se quitará ${if (isOutput) "la salida" else "el sensor"} del puerto " +
                    "${port ?: (idx + 1)}" +
                    (s?.let { " (${sensorTypeLabel(it.type)})" } ?: "") +
                    ". El puerto queda libre y conserva su número: los demás no se mueven.",
            )
            if (isOutput) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "La estación apaga su pin al quitarla: una válvula no puede quedarse " +
                        "abierta sin nadie que la controle.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!isOutput && cached > 0) {
                Spacer(Modifier.height(14.dp))
                Text(
                    "Tiene $cached ${if (cached == 1L) "lectura guardada" else "lecturas guardadas"}.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { dropHistory = !dropHistory },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = dropHistory, onCheckedChange = { dropHistory = it })
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Borrar también su histórico",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    if (dropHistory) "Se borrarán en el móvil y en la estación. No se puede deshacer."
                    else "Se conservan. Si más adelante asignas otro sensor a este puerto, " +
                        "verás las dos etapas juntas en su histórico.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Heading for one half of the list: the shared accent header, a one-line caption
 *  explaining what the half is for, and an optional action on the right. */
@Composable
private fun ListSection(
    title: String,
    caption: String,
    accent: Color = MaterialTheme.colorScheme.primary,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    actionEnabled: Boolean = true,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            SectionHeader(title, accent = accent)
            Spacer(Modifier.height(2.dp))
            Text(
                caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 14.dp),   // aligns under the title
            )
        }
        if (action != null && onAction != null) {
            TextButton(onClick = onAction, enabled = actionEnabled) { Text(action) }
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ListSectionEmpty(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 14.dp, bottom = 4.dp),
    )
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
