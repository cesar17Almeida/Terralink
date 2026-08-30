package com.astralink.terralink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.astralink.terralink.ble.protocol.ConfigPatchMsg
import com.astralink.terralink.ble.protocol.ConfigSnapshotMsg
import com.astralink.terralink.ble.protocol.DeviceInfo
import com.astralink.terralink.ble.session.ActiveSession
import com.astralink.terralink.ble.session.appliedSummary
import com.astralink.terralink.model.SavedStation
import com.astralink.terralink.state.ReadingsRepository
import com.astralink.terralink.ui.components.BackIconButton
import com.astralink.terralink.ui.components.PasswordField
import com.astralink.terralink.ui.components.SectionHeader
import com.astralink.terralink.ui.components.SettingsGroup
import com.astralink.terralink.ui.components.SettingsRowSpec
import com.astralink.terralink.ui.components.Spec
import com.astralink.terralink.ui.components.SpecTable
import com.astralink.terralink.ui.components.TerraDialog
import com.astralink.terralink.ui.components.TerraIcons
import com.astralink.terralink.ui.components.TerraTextField
import com.astralink.terralink.ui.components.rememberTimeOfDayPicker
import com.astralink.terralink.ui.components.dismissKeyboardOnTap
import com.astralink.terralink.util.formatTimeOfDay
import com.astralink.terralink.util.secondsToHuman
import com.astralink.terralink.util.systemUtcOffsetMinutes
import kotlinx.coroutines.launch

// Matches the firmware's accepted sleep range (SAVIA_SLEEP_MIN_S..MAX_S).
private const val SLEEP_MIN_S = 10
private const val SLEEP_MAX_S = 86_400

// Max advertised BLE name length (firmware SAVIA_BLE_NAME_MAX - 1).
private const val BLE_NAME_MAX = 20

// LoRa uplink cadence bounds (s): 5 min .. 24 h. Fair-use keeps this coarse.
private const val LORA_PERIOD_MIN_S = 300
private const val LORA_PERIOD_MAX_S = 86_400

// Global sensor capture cadence bounds (s): firmware SAVIA_CAPTURE_MIN_S..SAVIA_SLEEP_MAX_S.
private const val CAPTURE_MIN_S = 60
private const val CAPTURE_MAX_S = 86_400

private sealed class ConfigPhase {
    data object Loading : ConfigPhase()
    data class Ready(val snapshot: ConfigSnapshotMsg) : ConfigPhase()
    data class Failed(val message: String) : ConfigPhase()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigurationScreen(
    station: SavedStation,
    active: ActiveSession,
    onViewLogs: () -> Unit,
    onBack: () -> Unit,
) {
    var phase by remember { mutableStateOf<ConfigPhase>(ConfigPhase.Loading) }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(reloadKey) {
        phase = ConfigPhase.Loading
        phase = try {
            ConfigPhase.Ready(active.readConfig())
        } catch (e: Throwable) {
            ConfigPhase.Failed(e.message ?: "No se pudo leer la configuración")
        }
    }

    when (val p = phase) {
        ConfigPhase.Loading -> PlainConfigScaffold(onBack) { CenteredProgress("Leyendo configuración…") }
        is ConfigPhase.Failed -> PlainConfigScaffold(onBack) { ErrorPanel(p.message, onRetry = { reloadKey++ }) }
        is ConfigPhase.Ready -> ConfigReady(station, p.snapshot, active, onViewLogs, onBack)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlainConfigScaffold(onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configuración", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { BackIconButton(onClick = onBack) },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) { content() }
    }
}

// Which detail the config list is currently showing (Home = the list itself).
private enum class ConfigSection { Home, Station, Energy, Model, Location, Security, Dev, LocalData }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigReady(
    station: SavedStation,
    snapshot: ConfigSnapshotMsg,
    active: ActiveSession,
    onViewLogs: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val locationRequester = rememberLocationRequester()

    // Locally cached history for this station (persisted on every sync).
    var cachedCount by remember(station) { mutableStateOf<Long?>(null) }
    var deletingLocal by remember { mutableStateOf(false) }
    var confirmDeleteLocal by remember { mutableStateOf(false) }
    LaunchedEffect(station.bleId, deletingLocal) {
        cachedCount = runCatching { ReadingsRepository.countByStation(station.bleId) }.getOrNull()
    }

    // On connect, keep the station's UTC offset aligned with the phone's so daily_hour
    // is interpreted in local time. Silent: only utc_offset_min travels.
    LaunchedEffect(snapshot) {
        val sysOffset = systemUtcOffsetMinutes()
        if (snapshot.utcOffsetMin != sysOffset) {
            runCatching { active.writeConfig(ConfigPatchMsg(utcOffsetMin = sysOffset)) }
        }
    }

    // Committed = last value we know the station holds; updated on a successful save.
    var committedName by remember(snapshot) { mutableStateOf(snapshot.name) }
    var committedSleep by remember(snapshot) { mutableStateOf(snapshot.sleepS) }
    var committedDeep by remember(snapshot) { mutableStateOf(snapshot.deepSleep) }
    var committedMock by remember(snapshot) { mutableStateOf(snapshot.mockEnabled) }
    var committedLog by remember(snapshot) { mutableStateOf(snapshot.logLevel) }
    var committedDaily by remember(snapshot) { mutableStateOf(snapshot.dailyHour) }
    var committedDailyMin by remember(snapshot) { mutableStateOf(snapshot.dailyMin) }
    var committedLora by remember(snapshot) { mutableStateOf(snapshot.loraPeriodS) }
    var committedCapture by remember(snapshot) { mutableStateOf(snapshot.captureS) }
    var committedInfer by remember(snapshot) { mutableStateOf(snapshot.inferenceMode) }
    var committedLat by remember(snapshot) { mutableStateOf(snapshot.lat) }
    var committedLon by remember(snapshot) { mutableStateOf(snapshot.lon) }

    var nameText by remember(snapshot) { mutableStateOf(snapshot.name) }
    var deepSleep by remember(snapshot) { mutableStateOf(snapshot.deepSleep) }
    var sleepText by remember(snapshot) { mutableStateOf(snapshot.sleepS.toString()) }
    var mockEnabled by remember(snapshot) { mutableStateOf(snapshot.mockEnabled) }
    var logLevel by remember(snapshot) { mutableStateOf(snapshot.logLevel) }
    var dailyHour by remember(snapshot) { mutableStateOf(snapshot.dailyHour) }
    var dailyMin by remember(snapshot) { mutableStateOf(snapshot.dailyMin) }
    var loraText by remember(snapshot) { mutableStateOf(snapshot.loraPeriodS.toString()) }
    var captureText by remember(snapshot) { mutableStateOf(snapshot.captureS.toString()) }
    var inferMode by remember(snapshot) { mutableStateOf(snapshot.inferenceMode) }
    var latText by remember(snapshot) { mutableStateOf(snapshot.lat?.let { fmtCoord(it) } ?: "") }
    var lonText by remember(snapshot) { mutableStateOf(snapshot.lon?.let { fmtCoord(it) } ?: "") }
    var advanced by remember { mutableStateOf(false) }
    var section by remember { mutableStateOf(ConfigSection.Home) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var clearing by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    var prov by remember { mutableStateOf<Boolean?>(null) }   // a password is set?
    var pwDialog by remember { mutableStateOf(false) }
    var pwBusy by remember { mutableStateOf(false) }
    var pwError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(snapshot) { prov = runCatching { active.readAuthState().prov }.getOrNull() }

    val sleepValue = sleepText.trim().toIntOrNull()
    val sleepValid = sleepValue != null && sleepValue in SLEEP_MIN_S..SLEEP_MAX_S
    val nameTrimmed = nameText.trim()
    val nameValid = nameTrimmed.isNotEmpty() && nameTrimmed.length <= BLE_NAME_MAX
    val lora = loraText.trim().toIntOrNull()
    val loraValid = lora != null && lora in LORA_PERIOD_MIN_S..LORA_PERIOD_MAX_S
    val capture = captureText.trim().toIntOrNull()
    val captureValid = capture != null && capture in CAPTURE_MIN_S..CAPTURE_MAX_S
    val lat = latText.trim().toDoubleOrNull()
    val lon = lonText.trim().toDoubleOrNull()
    val coordsBothFilled = latText.isNotBlank() && lonText.isNotBlank()
    val coordsValid = lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0
    val coordsEntryOk = !coordsBothFilled || coordsValid
    val coordsChanged = coordsValid && (lat != committedLat || lon != committedLon)

    val allValid = sleepValid && nameValid && loraValid &&
        captureValid && coordsEntryOk
    val dirty = (nameValid && nameTrimmed != committedName) ||
        deepSleep != committedDeep ||
        (sleepValid && sleepValue != committedSleep) ||
        mockEnabled != committedMock ||
        logLevel != committedLog ||
        dailyHour != committedDaily || dailyMin != committedDailyMin ||
        (loraValid && lora != committedLora) ||
        (captureValid && capture != committedCapture) ||
        inferMode != committedInfer ||
        coordsChanged

    fun save() {
        saving = true
        error = null
        scope.launch {
            try {
                // Sparse on purpose: every field stays null unless it differs from the
                // value the station confirmed last, so a save carries only what moved.
                val patch = ConfigPatchMsg(
                    name = nameTrimmed.takeIf { nameValid && it != committedName },
                    sleepS = if (sleepValid && sleepValue != committedSleep) sleepValue else null,
                    deepSleep = if (deepSleep != committedDeep) deepSleep else null,
                    mock = if (mockEnabled != committedMock) mockEnabled else null,
                    logLevel = if (logLevel != committedLog) logLevel else null,
                    dailyHour = if (dailyHour != committedDaily) dailyHour else null,
                    dailyMin = if (dailyMin != committedDailyMin) dailyMin else null,
                    loraPeriodS = if (loraValid && lora != committedLora) lora else null,
                    captureS = if (captureValid && capture != committedCapture) capture else null,
                    inferenceMode = if (inferMode != committedInfer) inferMode else null,
                    lat = if (coordsChanged) lat else null,
                    lon = if (coordsChanged) lon else null,
                )
                val applied = active.writeConfig(patch)
                // Reflect the station's CONFIRMED state (this clears "dirty").
                committedName = applied.name; nameText = applied.name
                committedSleep = applied.sleepS; sleepText = applied.sleepS.toString()
                committedDeep = applied.deepSleep; deepSleep = applied.deepSleep
                committedMock = applied.mockEnabled; mockEnabled = applied.mockEnabled
                committedLog = applied.logLevel; logLevel = applied.logLevel
                committedDaily = applied.dailyHour; dailyHour = applied.dailyHour
                committedDailyMin = applied.dailyMin; dailyMin = applied.dailyMin
                committedLora = applied.loraPeriodS; loraText = applied.loraPeriodS.toString()
                committedCapture = applied.captureS; captureText = applied.captureS.toString()
                committedInfer = applied.inferenceMode; inferMode = applied.inferenceMode
                committedLat = applied.lat; committedLon = applied.lon
                latText = applied.lat?.let { fmtCoord(it) } ?: ""
                lonText = applied.lon?.let { fmtCoord(it) } ?: ""
                snackbarHostState.showSnackbar(patch.appliedSummary(applied))
            } catch (e: Throwable) {
                // Same channel the success path uses: an inline note alone is easy to
                // miss at the bottom of a long scroll.
                val msg = e.message ?: "No se pudo guardar la configuración"
                error = msg
                // Detached so the button stops spinning immediately instead of
                // waiting out the snackbar.
                scope.launch { snackbarHostState.showSnackbar(msg) }
            } finally {
                saving = false
            }
        }
    }

    fun clearLocation() {
        saving = true
        error = null
        scope.launch {
            try {
                val applied = active.clearCoords()
                committedLat = applied.lat; committedLon = applied.lon
                latText = ""; lonText = ""
                snackbarHostState.showSnackbar("Ubicación borrada ✓")
            } catch (e: Throwable) {
                val msg = e.message ?: "No se pudo borrar la ubicación"
                error = msg
                scope.launch { snackbarHostState.showSnackbar(msg) }
            } finally {
                saving = false
            }
        }
    }

    val sectionTitle = when (section) {
        ConfigSection.Home -> "Configuración"
        ConfigSection.Station -> "Estación"
        ConfigSection.Energy -> "Ahorro de energía"
        ConfigSection.Model -> "Modelo e inferencia"
        ConfigSection.Location -> "Ubicación"
        ConfigSection.Security -> "Seguridad"
        ConfigSection.Dev -> "Desarrollo"
        ConfigSection.LocalData -> "Datos guardados"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(sectionTitle, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    BackIconButton(onClick = {
                        if (section == ConfigSection.Home) onBack() else section = ConfigSection.Home
                    })
                },
                actions = {
                    // Save lives at the top level so edits made in any detail can be
                    // committed from anywhere; only shown when there is something to save.
                    if (dirty) {
                        TextButton(onClick = { save() }, enabled = allValid && !saving) {
                            Text(if (saving) "Guardando…" else "Guardar")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .dismissKeyboardOnTap()       // tap outside a field to hide the keyboard
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (section) {
                ConfigSection.Home -> ConfigHome(
                    snapshot = snapshot,
                    stationName = committedName,
                    deepSleep = deepSleep,
                    mockEnabled = mockEnabled,
                    inferMode = committedInfer,
                    dailyHour = committedDaily,
                    dailyMin = committedDailyMin,
                    hasCoords = committedLat != null && committedLon != null,
                    prov = prov,
                    localCount = cachedCount,
                    onOpen = { section = it },
                    onViewLogs = onViewLogs,
                )

                ConfigSection.Station -> StationCard(
                    snapshot = snapshot,
                    name = nameText,
                    onNameChange = { nameText = it.take(BLE_NAME_MAX); error = null },
                    nameValid = nameValid,
                    loraText = loraText,
                    onLoraChange = { loraText = it.filter { c -> c.isDigit() }; error = null },
                    loraValid = loraValid,
                    captureText = captureText,
                    onCaptureChange = { captureText = it.filter { c -> c.isDigit() }; error = null },
                    captureValid = captureValid,
                )

                ConfigSection.Energy -> EnergyCard(
                    deepSleep = deepSleep,
                    onToggle = { deepSleep = it; error = null },
                    advanced = advanced,
                    onToggleAdvanced = { advanced = !advanced },
                    sleepText = sleepText,
                    onSleepChange = { sleepText = it.filter { c -> c.isDigit() }; error = null },
                    sleepValid = sleepValid,
                )

                ConfigSection.Model -> ModelCard(
                    inferMode = inferMode,
                    inferDev = snapshot.inferDev,
                    onSelect = { inferMode = it; error = null },
                    dailyHour = dailyHour,
                    dailyMin = dailyMin,
                    onDailyChange = { h, m -> dailyHour = h; dailyMin = m; error = null },
                )

                ConfigSection.Location -> LocationCard(
                    latText = latText,
                    lonText = lonText,
                    onLatChange = { latText = filterSignedDecimal(it); error = null },
                    onLonChange = { lonText = filterSignedDecimal(it); error = null },
                    coordsEntryOk = coordsEntryOk,
                    hasCommitted = committedLat != null && committedLon != null,
                    busy = saving,
                    onClear = { clearLocation() },
                    onUseDevice = locationRequester?.let {
                        {
                            it.request { coords ->
                                if (coords != null) {
                                    latText = fmtCoord(coords.lat); lonText = fmtCoord(coords.lon); error = null
                                } else {
                                    scope.launch { snackbarHostState.showSnackbar("No se pudo obtener la ubicación") }
                                }
                            }
                        }
                    },
                )

                ConfigSection.Security -> SecurityCard(prov = prov, onManage = { pwDialog = true })

                ConfigSection.Dev -> DevCard(
                    mockEnabled = mockEnabled,
                    onMockToggle = { mockEnabled = it; error = null },
                    logLevel = logLevel,
                    onLogLevelChange = { logLevel = it; error = null },
                    clearing = clearing,
                    onClearData = { confirmClear = true },
                )

                ConfigSection.LocalData -> LocalDataCard(
                    count = cachedCount,
                    deleting = deletingLocal,
                    onDelete = { confirmDeleteLocal = true },
                )
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (confirmDeleteLocal) {
        TerraDialog(
            onDismiss = { confirmDeleteLocal = false },
            title = "¿Eliminar datos guardados?",
            confirmText = "Eliminar",
            destructive = true,
            onConfirm = {
                confirmDeleteLocal = false
                deletingLocal = true
                scope.launch {
                    try {
                        ReadingsRepository.deleteByStation(station.bleId)
                        snackbarHostState.showSnackbar("Datos del teléfono eliminados ✓")
                    } finally {
                        deletingLocal = false
                    }
                }
            },
        ) {
            Text(
                "Se eliminarán las lecturas históricas de esta estación guardadas en este " +
                    "teléfono. Los datos en el dispositivo no se tocan; puedes volver a " +
                    "descargarlas sincronizando.",
            )
        }
    }

    if (confirmClear) {
        TerraDialog(
            onDismiss = { confirmClear = false },
            title = "¿Borrar todos los datos?",
            confirmText = "Borrar",
            destructive = true,
            onConfirm = {
                confirmClear = false
                clearing = true
                scope.launch {
                    try { active.clearData() } catch (_: Throwable) {} finally { clearing = false }
                }
            },
        ) {
            Text("Se eliminarán todas las lecturas y predicciones guardadas en el dispositivo.")
        }
    }

    if (pwDialog) {
        PasswordDialog(
            provisioned = prov == true,
            busy = pwBusy,
            error = pwError,
            onDismiss = { pwDialog = false; pwError = null },
            onSubmit = { old, new ->
                pwBusy = true
                pwError = null
                scope.launch {
                    try {
                        if (prov == true) {
                            if (active.changePassword(old, new)) {
                                pwDialog = false
                                snackbarHostState.showSnackbar("Contraseña cambiada ✓")
                            } else {
                                pwError = "Contraseña actual incorrecta"
                            }
                        } else {
                            active.setPassword(new)
                            prov = runCatching { active.readAuthState().prov }.getOrNull() ?: true
                            pwDialog = false
                            snackbarHostState.showSnackbar("Contraseña establecida ✓")
                        }
                    } catch (e: Throwable) {
                        pwError = e.message ?: "No se pudo guardar la contraseña"
                    } finally {
                        pwBusy = false
                    }
                }
            },
        )
    }
}

/** The settings list itself: a device hero card on top, then grouped iOS-style rows. */
@Composable
private fun ConfigHome(
    snapshot: ConfigSnapshotMsg,
    stationName: String,
    deepSleep: Boolean,
    mockEnabled: Boolean,
    inferMode: String,
    dailyHour: Int,
    dailyMin: Int,
    hasCoords: Boolean,
    prov: Boolean?,
    localCount: Long?,
    onOpen: (ConfigSection) -> Unit,
    onViewLogs: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme

    DeviceHeroCard(snapshot.device)

    SettingsGroup(
        header = "Ajustes",
        rows = listOf(
            SettingsRowSpec(
                icon = TerraIcons.Antenna,
                title = "Estación",
                value = stationName,
                container = scheme.primaryContainer,
                content = scheme.onPrimaryContainer,
                onClick = { onOpen(ConfigSection.Station) },
            ),
            SettingsRowSpec(
                icon = TerraIcons.Bolt,
                title = "Ahorro de energía",
                value = if (deepSleep) "Activado" else "Desactivado",
                container = scheme.tertiaryContainer,
                content = scheme.onTertiaryContainer,
                onClick = { onOpen(ConfigSection.Energy) },
            ),
            SettingsRowSpec(
                icon = TerraIcons.Memory,
                title = "Modelo e inferencia",
                value = if (inferMode == "local") "En el dispositivo · ${formatTimeOfDay(dailyHour, dailyMin)}"
                        else "En la nube/app",
                container = scheme.secondaryContainer,
                content = scheme.onSecondaryContainer,
                onClick = { onOpen(ConfigSection.Model) },
            ),
            SettingsRowSpec(
                icon = TerraIcons.WaterDrop,
                title = "Ubicación",
                value = if (hasCoords) "Definida" else "Sin definir",
                onClick = { onOpen(ConfigSection.Location) },
            ),
            SettingsRowSpec(
                icon = TerraIcons.Lock,
                title = "Seguridad",
                value = when (prov) {
                    true -> "Protegida"
                    false -> "Sin contraseña"
                    null -> "…"
                },
                onClick = { onOpen(ConfigSection.Security) },
            ),
        ),
    )

    SettingsGroup(
        header = "Avanzado",
        rows = listOf(
            SettingsRowSpec(
                icon = TerraIcons.Memory,
                title = "Desarrollo",
                value = if (mockEnabled) "Mock ON" else null,
                onClick = { onOpen(ConfigSection.Dev) },
            ),
            SettingsRowSpec(
                icon = TerraIcons.Terminal,
                title = "Logs del dispositivo",
                onClick = onViewLogs,
            ),
        ),
    )

    SettingsGroup(
        header = "Datos",
        rows = listOf(
            SettingsRowSpec(
                icon = TerraIcons.Delete,
                title = "Datos guardados",
                value = localCount?.let { if (it == 0L) "Vacío" else "$it" },
                container = scheme.errorContainer,
                content = scheme.onErrorContainer,
                onClick = { onOpen(ConfigSection.LocalData) },
            ),
        ),
    )
}

@Composable
private fun SecurityCard(prov: Boolean?, onManage: () -> Unit) {
    val statusColor = when (prov) {
        true -> MaterialTheme.colorScheme.primary
        false -> MaterialTheme.colorScheme.error
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(modifier = Modifier.padding(20.dp)) {
            SectionHeader("Seguridad", accent = statusColor)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(8.dp).background(statusColor, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = when (prov) {
                        true -> "Protegida con contraseña."
                        false -> "Sin contraseña: cualquiera con la app puede controlarla."
                        null -> "Comprobando…"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onManage,
                enabled = prov != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (prov == true) "Cambiar contraseña" else "Establecer contraseña")
            }
        }
    }
}

@Composable
private fun PasswordDialog(
    provisioned: Boolean,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSubmit: (old: String, new: String) -> Unit,
) {
    var old by remember { mutableStateOf("") }
    var newPw by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val mismatch = confirm.isNotEmpty() && newPw != confirm
    val canSubmit = newPw.length >= 4 && newPw == confirm && (!provisioned || old.isNotEmpty()) && !busy
    TerraDialog(
        onDismiss = onDismiss,
        title = if (provisioned) "Cambiar contraseña" else "Establecer contraseña",
        confirmText = if (busy) "Guardando…" else "Guardar",
        onConfirm = { onSubmit(old, newPw) },
        confirmEnabled = canSubmit,
    ) {
        if (provisioned) {
            PasswordField(
                value = old, onValueChange = { old = it },
                label = "Contraseña actual",
            )
            Spacer(Modifier.height(8.dp))
        }
        PasswordField(
            value = newPw, onValueChange = { newPw = it },
            label = "Nueva contraseña (mín. 4)",
        )
        Spacer(Modifier.height(8.dp))
        PasswordField(
            value = confirm, onValueChange = { confirm = it },
            label = "Repite la nueva",
            isError = mismatch || error != null,
            supportingText = {
                if (mismatch) Text("No coinciden")
                else error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun DevCard(
    mockEnabled: Boolean,
    onMockToggle: (Boolean) -> Unit,
    logLevel: Int,
    onLogLevelChange: (Int) -> Unit,
    clearing: Boolean,
    onClearData: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(modifier = Modifier.padding(20.dp)) {
            SectionHeader("Desarrollo", accent = MaterialTheme.colorScheme.secondary)

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Modo desarrollador (mock)", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "Genera lecturas de prueba y muestra las herramientas de simulación",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = mockEnabled, onCheckedChange = onMockToggle)
            }

            Spacer(Modifier.height(16.dp))
            Text("Nivel de logs", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = logLevel == 1, onClick = { onLogLevelChange(1) },
                    label = { Text("Info") })
                FilterChip(selected = logLevel == 0, onClick = { onLogLevelChange(0) },
                    label = { Text("Debug") })
            }

            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = onClearData,
                enabled = !clearing,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(if (clearing) "Limpiando…" else "Limpiar todos los datos")
            }
        }
    }
}

@Composable
private fun LocalDataCard(count: Long?, deleting: Boolean, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(modifier = Modifier.padding(20.dp)) {
            SectionHeader("Datos guardados", accent = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
            Text(
                text = when (count) {
                    null -> "Lecturas históricas guardadas en este teléfono."
                    0L -> "No hay lecturas guardadas en este teléfono."
                    else -> "$count lecturas guardadas en este teléfono."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onDelete,
                enabled = !deleting && (count == null || count > 0L),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error),
            ) {
                Icon(TerraIcons.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (deleting) "Eliminando…" else "Eliminar datos históricos")
            }
        }
    }
}

/**
 * Station detail: one card per concern (identity, schedule, capture, LoRa).
 * Emitted as siblings so the parent column's 16.dp spacing separates them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StationCard(
    snapshot: ConfigSnapshotMsg,
    name: String,
    onNameChange: (String) -> Unit,
    nameValid: Boolean,
    loraText: String,
    onLoraChange: (String) -> Unit,
    loraValid: Boolean,
    captureText: String,
    onCaptureChange: (String) -> Unit,
    captureValid: Boolean,
) {
    // -- Identity ------------------------------------------------------------
    ConfigGroupCard(title = "Identidad") {
        TerraTextField(
            value = name,
            onValueChange = onNameChange,
            label = "Nombre BLE",
            isError = name.isNotEmpty() && !nameValid,
            supportingText = {
                Text(
                    if (name.isNotEmpty() && !nameValid) "1 a $BLE_NAME_MAX caracteres"
                    else "Con el que aparece al buscar por Bluetooth",
                )
            },
        )
        Spacer(Modifier.height(8.dp))
        SpecTable(rows = listOf(Spec("Botón de reactivación", "GPIO ${snapshot.wakeGpio}")))
    }

    // -- Sensor capture ------------------------------------------------------
    ConfigGroupCard(title = "Captura de sensores") {
        TerraTextField(
            value = captureText,
            onValueChange = onCaptureChange,
            label = "Cadencia global (segundos)",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = captureText.isNotEmpty() && !captureValid,
            supportingText = {
                if (captureText.isNotEmpty() && !captureValid) {
                    Text("Entre $CAPTURE_MIN_S s (1 min) y $CAPTURE_MAX_S s (24 h)")
                } else {
                    Text(
                        captureText.trim().toIntOrNull()
                            ?.let { "Lectura cada ${secondsToHuman(it)}" } ?: "",
                    )
                }
            },
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Los sensores con un intervalo propio configurado ignoran este valor global.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // -- LoRa ----------------------------------------------------------------
    ConfigGroupCard(title = "LoRa", accent = MaterialTheme.colorScheme.secondary) {
        TerraTextField(
            value = loraText,
            onValueChange = onLoraChange,
            label = "Periodo de envío (segundos)",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = loraText.isNotEmpty() && !loraValid,
            supportingText = {
                if (loraText.isNotEmpty() && !loraValid) {
                    Text("Entre $LORA_PERIOD_MIN_S s (5 min) y $LORA_PERIOD_MAX_S s (24 h)")
                } else {
                    Text(
                        loraText.trim().toIntOrNull()
                            ?.let { "Envío por radio cada ${secondsToHuman(it)}" } ?: "",
                    )
                }
            },
        )
    }
}

/** Rounded card with a [SectionHeader], optional gray subtitle and 20.dp padding. */
@Composable
private fun ConfigGroupCard(
    title: String,
    accent: Color = MaterialTheme.colorScheme.primary,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(modifier = Modifier.padding(20.dp)) {
            SectionHeader(title, accent = accent)
            if (subtitle != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelCard(
    inferMode: String,
    inferDev: Boolean,
    onSelect: (String) -> Unit,
    dailyHour: Int,
    dailyMin: Int,
    onDailyChange: (Int, Int) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(modifier = Modifier.padding(20.dp)) {
            SectionHeader("¿Dónde se ejecuta el modelo?", accent = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = inferMode == "local",
                    onClick = { if (inferDev) onSelect("local") },
                    enabled = inferDev,
                    label = { Text("En el dispositivo") },
                )
                FilterChip(
                    selected = inferMode == "forward",
                    onClick = { onSelect("forward") },
                    label = { Text("En la nube/app") },
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = when {
                    !inferDev -> "Este firmware no incluye el modelo; la inferencia se hace en la nube o en la app."
                    inferMode == "local" -> "El LSTM se ejecuta en la estación."
                    else -> "La estación sirve los datos; el modelo se ejecuta en la nube o en la app."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Spacer(Modifier.height(16.dp))

    ConfigGroupCard(
        title = "Predicción automática",
        accent = MaterialTheme.colorScheme.secondary,
        subtitle = "Hora local del teléfono. Por defecto las 20:00.",
    ) {
        val pickTime = rememberTimeOfDayPicker(onPick = onDailyChange)
        Surface(
            onClick = { pickTime(dailyHour, dailyMin) },
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Hora de predicción",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        formatTimeOfDay(dailyHour, dailyMin),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    "Cambiar",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Cada día a las ${formatTimeOfDay(dailyHour, dailyMin)} la estación mide y ejecuta el modelo.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = if (inferMode == "local") {
                "Mide justo antes de predecir. Necesita 24 h de humedad y el pronóstico de temperatura."
            } else {
                "Con el modelo fuera de la estación esta hora no predice; se conserva por si vuelves al modo local."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LocationCard(
    latText: String,
    lonText: String,
    onLatChange: (String) -> Unit,
    onLonChange: (String) -> Unit,
    coordsEntryOk: Boolean,
    hasCommitted: Boolean,
    busy: Boolean,
    onClear: () -> Unit,
    onUseDevice: (() -> Unit)?,
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(modifier = Modifier.padding(20.dp)) {
            SectionHeader("Ubicación", accent = MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.height(6.dp))
            Text(
                "Coordenadas de la estación, usadas para el pronóstico meteorológico. " +
                    "Introduce ambas juntas o déjalas vacías.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TerraTextField(
                    value = latText,
                    onValueChange = onLatChange,
                    label = "Latitud",
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = !coordsEntryOk,
                )
                TerraTextField(
                    value = lonText,
                    onValueChange = onLonChange,
                    label = "Longitud",
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = !coordsEntryOk,
                )
            }
            if (!coordsEntryOk) {
                Spacer(Modifier.height(4.dp))
                Text("Latitud −90..90 y longitud −180..180", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
            if (onUseDevice != null) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onUseDevice, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Usar mi ubicación")
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onClear,
                enabled = !busy && hasCommitted,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text("Borrar ubicación")
            }
            Text(
                "Guardar aplica las coordenadas escritas; \"Borrar\" las elimina en la estación.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun DeviceHeroCard(device: DeviceInfo) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = TerraIcons.Memory,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(34.dp),
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        text = device.model,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = device.mcu,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            SpecTable(rows = listOf(Spec("Firmware", "savia ${device.fw}")))
        }
    }
}

@Composable
private fun EnergyCard(
    deepSleep: Boolean,
    onToggle: (Boolean) -> Unit,
    advanced: Boolean,
    onToggleAdvanced: () -> Unit,
    sleepText: String,
    onSleepChange: (String) -> Unit,
    sleepValid: Boolean,
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    SectionHeader("Ahorro de energía", accent = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (deepSleep) "El equipo duerme entre ciclos (radio apagada)"
                               else "El equipo permanece despierto y visible",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = deepSleep, onCheckedChange = onToggle)
            }

            if (deepSleep) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Mientras duerme deja de anunciarse; pulsa el botón físico de la " +
                        "estación para reconectar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = if (advanced) "Avanzado ▴" else "Avanzado ▾",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onToggleAdvanced),
            )

            if (advanced) {
                Spacer(Modifier.height(12.dp))
                TerraTextField(
                    value = sleepText,
                    onValueChange = onSleepChange,
                    label = "Tiempo de sueño (segundos)",
                    isError = sleepText.isNotEmpty() && !sleepValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = {
                        if (sleepText.isNotEmpty() && !sleepValid) {
                            Text("Entre $SLEEP_MIN_S s y $SLEEP_MAX_S s (24 h)")
                        } else {
                            Text(sleepText.toIntOrNull()?.let { "= ${secondsToHuman(it)}" } ?: "")
                        }
                    },
                )
                Spacer(Modifier.height(8.dp))
                SleepPresets(onPick = { onSleepChange(it.toString()) })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SleepPresets(onPick: (Int) -> Unit) {
    // Sleep is capped by the hourly capture, so anything up to 1 h is the useful range.
    val presets = listOf(300 to "5 min", 600 to "10 min", 1800 to "30 min", 3600 to "1 h")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        presets.forEach { (secs, label) ->
            AssistChip(onClick = { onPick(secs) }, label = { Text(label) })
        }
    }
}

// --- small shared pieces ----------------------------------------------------

@Composable
private fun CenteredProgress(text: String) {
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
private fun ErrorPanel(message: String, onRetry: () -> Unit) {
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

// Keep only a leading '-', digits and a single '.', so a coordinate field never
// holds a value String.toDoubleOrNull can't parse.
private fun filterSignedDecimal(s: String): String {
    val sb = StringBuilder()
    var dot = false
    s.forEachIndexed { i, c ->
        when {
            c == '-' && i == 0 -> sb.append(c)
            c == '.' && !dot -> { dot = true; sb.append(c) }
            c.isDigit() -> sb.append(c)
        }
    }
    return sb.toString()
}

// Double -> short decimal string (<= 6 dp, trailing zeros trimmed), no platform format.
private fun fmtCoord(v: Double): String {
    val neg = v < 0
    val scaled = kotlin.math.round(kotlin.math.abs(v) * 1_000_000).toLong()
    val whole = scaled / 1_000_000
    val frac = (scaled % 1_000_000).toString().padStart(6, '0').trimEnd('0')
    val sign = if (neg && (whole != 0L || frac.isNotEmpty())) "-" else ""
    return if (frac.isEmpty()) "$sign$whole" else "$sign$whole.$frac"
}
