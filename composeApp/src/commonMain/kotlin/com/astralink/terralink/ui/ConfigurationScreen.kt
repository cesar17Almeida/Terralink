package com.astralink.terralink.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astralink.terralink.ble.protocol.ConfigPatchMsg
import com.astralink.terralink.ble.protocol.ConfigSnapshotMsg
import com.astralink.terralink.ble.protocol.DeviceInfo
import com.astralink.terralink.ble.session.ActiveSession
import com.astralink.terralink.model.SavedStation
import com.astralink.terralink.ui.components.BackIconButton
import com.astralink.terralink.ui.components.PasswordField
import kotlinx.coroutines.launch

// Matches the firmware's accepted sleep range (SAVIA_SLEEP_MIN_S..MAX_S).
private const val SLEEP_MIN_S = 10
private const val SLEEP_MAX_S = 86_400

// Max advertised BLE name length (firmware SAVIA_BLE_NAME_MAX - 1).
private const val BLE_NAME_MAX = 20

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
        is ConfigPhase.Ready -> ConfigReady(p.snapshot, active, onViewLogs, onBack)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigReady(
    snapshot: ConfigSnapshotMsg,
    active: ActiveSession,
    onViewLogs: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Committed = last value we know the station holds; updated on a successful save.
    var committedName by remember(snapshot) { mutableStateOf(snapshot.name) }
    var committedSleep by remember(snapshot) { mutableStateOf(snapshot.sleepS) }
    var committedDeep by remember(snapshot) { mutableStateOf(snapshot.deepSleep) }
    var committedMock by remember(snapshot) { mutableStateOf(snapshot.mockEnabled) }
    var committedLog by remember(snapshot) { mutableStateOf(snapshot.logLevel) }

    var nameText by remember(snapshot) { mutableStateOf(snapshot.name) }
    var deepSleep by remember(snapshot) { mutableStateOf(snapshot.deepSleep) }
    var sleepText by remember(snapshot) { mutableStateOf(snapshot.sleepS.toString()) }
    var mockEnabled by remember(snapshot) { mutableStateOf(snapshot.mockEnabled) }
    var logLevel by remember(snapshot) { mutableStateOf(snapshot.logLevel) }
    var advanced by remember { mutableStateOf(false) }
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
    val dirty = (nameValid && nameTrimmed != committedName) ||
        deepSleep != committedDeep ||
        (sleepValid && sleepValue != committedSleep) ||
        mockEnabled != committedMock ||
        logLevel != committedLog

    fun save() {
        val newSleep = if (sleepValid) sleepValue else null
        saving = true
        error = null
        scope.launch {
            try {
                val applied = active.writeConfig(
                    ConfigPatchMsg(
                        name = nameTrimmed.takeIf { nameValid && it != committedName },
                        sleepS = newSleep?.takeIf { it != committedSleep },
                        deepSleep = if (deepSleep != committedDeep) deepSleep else null,
                        mock = if (mockEnabled != committedMock) mockEnabled else null,
                        logLevel = if (logLevel != committedLog) logLevel else null,
                    ),
                )
                // Reflect the station's CONFIRMED state (this clears "dirty").
                committedName = applied.name; nameText = applied.name
                committedSleep = applied.sleepS; sleepText = applied.sleepS.toString()
                committedDeep = applied.deepSleep; deepSleep = applied.deepSleep
                committedMock = applied.mockEnabled; mockEnabled = applied.mockEnabled
                committedLog = applied.logLevel; logLevel = applied.logLevel
                snackbarHostState.showSnackbar(
                    "Aplicado ✓ · sueño ${secondsToHuman(applied.sleepS)} · " +
                        "ahorro ${if (applied.deepSleep) "ON" else "OFF"}",
                )
            } catch (e: Throwable) {
                error = e.message ?: "No se pudo guardar la configuración"
            } finally {
                saving = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configuración", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { BackIconButton(onClick = onBack) },
                actions = {
                    TextButton(onClick = { save() }, enabled = dirty && sleepValid && nameValid && !saving) {
                        Text(if (saving) "Guardando…" else "Guardar")
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            DeviceHeroCard(snapshot.device)

            EnergyCard(
                deepSleep = deepSleep,
                onToggle = { deepSleep = it; error = null },
                advanced = advanced,
                onToggleAdvanced = { advanced = !advanced },
                sleepText = sleepText,
                onSleepChange = { sleepText = it.filter { c -> c.isDigit() }; error = null },
                sleepValid = sleepValid,
            )

            StationCard(
                snapshot = snapshot,
                name = nameText,
                onNameChange = { nameText = it.take(BLE_NAME_MAX); error = null },
                nameValid = nameValid,
            )

            DevCard(
                mockEnabled = mockEnabled,
                onMockToggle = { mockEnabled = it; error = null },
                logLevel = logLevel,
                onLogLevelChange = { logLevel = it; error = null },
                clearing = clearing,
                onClearData = { confirmClear = true },
            )

            SecurityCard(prov = prov, onManage = { pwDialog = true })

            OutlinedButton(onClick = onViewLogs, modifier = Modifier.fillMaxWidth()) {
                Text("Ver logs del dispositivo")
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("¿Borrar todos los datos?") },
            text = { Text("Se eliminarán todas las lecturas y predicciones guardadas en el dispositivo.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    clearing = true
                    scope.launch {
                        try { active.clearData() } catch (_: Throwable) {} finally { clearing = false }
                    }
                }) { Text("Borrar") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancelar") } },
        )
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

@Composable
private fun SecurityCard(prov: Boolean?, onManage: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Seguridad", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Text(
                text = when (prov) {
                    true -> "Protegida con contraseña."
                    false -> "Sin contraseña: cualquiera con la app puede controlarla."
                    null -> "Comprobando…"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (provisioned) "Cambiar contraseña" else "Establecer contraseña") },
        text = {
            Column {
                if (provisioned) {
                    PasswordField(
                        value = old, onValueChange = { old = it },
                        label = "Contraseña actual", modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                }
                PasswordField(
                    value = newPw, onValueChange = { newPw = it },
                    label = "Nueva contraseña (mín. 4)", modifier = Modifier.fillMaxWidth(),
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
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(old, newPw) }, enabled = canSubmit) {
                Text(if (busy) "Guardando…" else "Guardar")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
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
            Text("Desarrollo", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Datos simulados (mock)", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "Genera lecturas de prueba en el dispositivo",
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StationCard(
    snapshot: ConfigSnapshotMsg,
    name: String,
    onNameChange: (String) -> Unit,
    nameValid: Boolean,
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Estación", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                label = { Text("Nombre BLE") },
                singleLine = true,
                isError = name.isNotEmpty() && !nameValid,
                supportingText = {
                    Text(
                        if (name.isNotEmpty() && !nameValid) "1 a $BLE_NAME_MAX caracteres"
                        else "Con el que aparece al buscar por Bluetooth",
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            InfoRow("Captura", "cada ${secondsToHuman(snapshot.captureS)}")
            Spacer(Modifier.height(6.dp))
            InfoRow("Ciclo diario", "${snapshot.dailyHour}:00 UTC")
            snapshot.sensors.forEach { s ->
                Spacer(Modifier.height(6.dp))
                InfoRow("Sensor ${s.port}", "GPIO ${s.gpio} · ${s.type}")
            }
            Spacer(Modifier.height(6.dp))
            InfoRow("Botón", "GPIO ${snapshot.wakeGpio}")
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
                // Placeholder for the device PNG (keyed by device.img). Drop a
                // real image into composeResources/drawable and swap this Box.
                Box(
                    modifier = Modifier.size(72.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = deviceEmoji(device.img), fontSize = 44.sp)
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
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            InfoRow("Firmware", "savia ${device.fw}")
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
                    Text("Ahorro de energía", style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium)
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
                OutlinedTextField(
                    value = sleepText,
                    onValueChange = onSleepChange,
                    label = { Text("Tiempo de sueño (segundos)") },
                    singleLine = true,
                    isError = sleepText.isNotEmpty() && !sleepValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = {
                        if (sleepText.isNotEmpty() && !sleepValid) {
                            Text("Entre $SLEEP_MIN_S s y $SLEEP_MAX_S s (24 h)")
                        } else {
                            Text(sleepText.toIntOrNull()?.let { "= ${secondsToHuman(it)}" } ?: "")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
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
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            modifier = Modifier.weight(0.42f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            modifier = Modifier.weight(0.58f),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

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

private fun deviceEmoji(img: String): String = when {
    img.contains("pi_zero") || img.contains("raspi") || img.contains("pi4") -> "🍓" // 🍓
    img.contains("pico") -> "🔲" // 🔲
    else -> "🔌" // 🔌
}

private fun secondsToHuman(s: Int): String = when {
    s % 3600 == 0 && s >= 3600 -> "${s / 3600} h"
    s % 60 == 0 && s >= 60 -> "${s / 60} min"
    else -> "$s s"
}
