package com.astralink.terralink.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.astralink.terralink.ble.protocol.ConfigPatchMsg
import com.astralink.terralink.ble.protocol.ConfigSnapshotMsg
import com.astralink.terralink.ble.protocol.PinmapMsg
import com.astralink.terralink.ble.protocol.SensorInfo
import com.astralink.terralink.ble.session.ActiveSession
import com.astralink.terralink.model.SavedStation
import com.astralink.terralink.state.StationsRepository
import com.astralink.terralink.ui.components.BackIconButton
import com.astralink.terralink.ui.components.ListItemsCard
import com.astralink.terralink.ui.components.PasswordField
import com.astralink.terralink.ui.components.TerraIcons
import com.astralink.terralink.ui.components.dismissKeyboardOnTap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Steps of the first-run wizard; INTRO offers to configure now or later. */
enum class SetupStep { INTRO, SENSORS, LORA, PASSWORD, MODEL }

/** How long the closing screen stays before handing back to the station. */
private const val SUCCESS_HOLD_MS = 1600L

private sealed class SetupPhase {
    data object Loading : SetupPhase()
    data class Ready(val config: ConfigSnapshotMsg, val pinmap: PinmapMsg?, val prov: Boolean) : SetupPhase()
    data class Failed(val message: String) : SetupPhase()
}

/**
 * First-run setup for a station that reports factory defaults: sensors, LoRa
 * module, password and model, one screen each. Sensors and the module are added
 * through their own dialogs, which write at once; password and model write on
 * "next", so a refused write is shown where it happened. Leaving by any door
 * dismisses the wizard until the station stops reporting factory defaults.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupWizardScreen(
    station: SavedStation,
    active: ActiveSession,
    initialStep: SetupStep,
    onAddSensors: () -> Unit,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val snackbar = remember { SnackbarHostState() }
    var phase by remember { mutableStateOf<SetupPhase>(SetupPhase.Loading) }
    var step by remember { mutableStateOf(initialStep) }
    var finished by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }
    var loraDialog by remember { mutableStateOf<LoraDialog?>(null) }

    LaunchedEffect(reloadKey) {
        phase = SetupPhase.Loading
        phase = try {
            SetupPhase.Ready(
                config = active.readConfig(),
                pinmap = runCatching { active.readPinmap() }.getOrNull(),
                prov = runCatching { active.readAuthState().prov }.getOrDefault(false),
            )
        } catch (e: Throwable) {
            SetupPhase.Failed(e.message ?: "No se pudo leer la estación")
        }
    }

    // Drafts re-seed from the station's confirmed config after every write.
    val cfg = (phase as? SetupPhase.Ready)?.config
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var mode by remember(cfg) { mutableStateOf(cfg?.inferenceMode ?: "forward") }
    var hour by remember(cfg) { mutableStateOf(cfg?.dailyHour ?: 20) }
    var minute by remember(cfg) { mutableStateOf(cfg?.dailyMin ?: 0) }

    fun leave() { StationsRepository.setSetupSkipped(station.bleId, true); onDone() }
    fun back() { if (step == SetupStep.INTRO) leave() else step = SetupStep.entries[step.ordinal - 1] }

    /** Last step landed: confirm with a haptic tick and a toast, then hand back. */
    fun finish() {
        finished = true
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        scope.launch {
            launch { snackbar.showSnackbar("Configuración inicial guardada ✓") }
            delay(SUCCESS_HOLD_MS)
            leave()
        }
    }
    fun advance() { if (step == SetupStep.MODEL) finish() else step = SetupStep.entries[step.ordinal + 1] }

    /** Write one step's change; advance only once the station confirmed it. */
    fun commit(write: suspend () -> Unit) {
        busy = true; error = null
        scope.launch {
            try { write(); advance() }
            catch (e: Throwable) { error = e.message ?: "No se pudo guardar" }
            finally { busy = false }
        }
    }

    fun next() {
        val ready = phase as? SetupPhase.Ready ?: return
        val c = ready.config
        when (step) {
            SetupStep.PASSWORD ->
                if (password.isEmpty() || ready.prov) advance()
                else commit { active.setPassword(password); phase = ready.copy(prov = true) }
            SetupStep.MODEL -> {
                val patch = ConfigPatchMsg(
                    inferenceMode = mode.takeIf { it != c.inferenceMode },
                    dailyHour = hour.takeIf { it != c.dailyHour },
                    dailyMin = minute.takeIf { it != c.dailyMin },
                )
                if (patch == ConfigPatchMsg()) advance() else commit { active.writeConfig(patch) }
            }
            else -> advance()
        }
    }

    val passwordOk = password == confirm && (password.isEmpty() || password.length >= 4)
    val canNext = cfg != null && (step != SetupStep.PASSWORD || passwordOk)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configuración inicial", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { BackIconButton(onClick = ::back) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (!finished) WizardBar(
                lastStep = step == SetupStep.MODEL,
                firstStep = step == SetupStep.INTRO,
                busy = busy,
                canNext = canNext,
                canSave = canNext,
                onBack = ::back,
                onNext = ::next,
                onSave = ::next,
                backLabel = if (step == SetupStep.INTRO) "Ahora no" else null,
                nextLabel = if (step == SetupStep.INTRO) "Configurar" else "Siguiente",
                saveLabel = "Finalizar",
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .dismissKeyboardOnTap()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (val p = phase) {
                SetupPhase.Loading -> Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) {
                    CircularProgressIndicator()
                }
                is SetupPhase.Failed -> {
                    Text(p.message, color = MaterialTheme.colorScheme.error)
                    Button(onClick = { reloadKey++ }) { Text("Reintentar") }
                }
                is SetupPhase.Ready -> if (finished) {
                    Centerpiece(TerraIcons.Check, "Estación configurada", "Ya puedes usarla desde su pantalla.")
                } else {
                    if (step != SetupStep.INTRO) StepHeader(step)
                    when (step) {
                        SetupStep.INTRO -> Centerpiece(
                            TerraIcons.Memory, "Estación nueva",
                            "«${station.displayName}» no tiene ninguna configuración guardada. Puedes " +
                                "prepararla ahora en cuatro pasos (sensores, conectividad, contraseña y " +
                                "modelo) o hacerlo más tarde desde Configurar y Periféricos.",
                        )
                        SetupStep.SENSORS -> SensorsStep(p.config.sensors, onAdd = onAddSensors)
                        SetupStep.LORA -> LoraStep(
                            module = loraPeripheral(p.config),
                            onAdd = { loraDialog = LoraDialog.EDIT },
                            onEdit = { loraDialog = LoraDialog.EDIT },
                            onRemove = { loraDialog = LoraDialog.REMOVE },
                        )
                        SetupStep.PASSWORD -> PasswordStep(
                            prov = p.prov, password = password, confirm = confirm,
                            mismatch = confirm.isNotEmpty() && password != confirm,
                            onPassword = { password = it }, onConfirm = { confirm = it },
                        )
                        SetupStep.MODEL -> ModelCard(
                            inferMode = mode, inferDev = p.config.inferDev, onSelect = { mode = it },
                            dailyHour = hour, dailyMin = minute,
                            onDailyChange = { h, m -> hour = h; minute = m },
                        )
                    }
                    error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    LoraModuleDialogs(
                        active = active, config = p.config, freePins = p.pinmap?.freePins(),
                        dialog = loraDialog, onClose = { loraDialog = null },
                        onChanged = { phase = p.copy(config = it) },
                    )
                }
            }
        }
    }
}

private fun stepTitle(step: SetupStep): String = when (step) {
    SetupStep.INTRO -> "Estación nueva"
    SetupStep.SENSORS -> "Sensores"
    SetupStep.LORA -> "Conectividad LoRa"
    SetupStep.PASSWORD -> "Contraseña"
    SetupStep.MODEL -> "Modelo y hora"
}

@Composable
private fun StepHeader(step: SetupStep) {
    Column {
        Text(stepTitle(step), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Paso ${step.ordinal} de ${SetupStep.entries.size - 1}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Opening and closing image: a breathing ring behind an icon, title and text centered. */
@Composable
private fun Centerpiece(icon: ImageVector, title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Hero(icon)
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.padding(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Hero(icon: ImageVector) {
    // One ring per cycle: grows from the disc's edge outward while fading away.
    val ring by rememberInfiniteTransition(label = "hero").animateFloat(
        initialValue = 0f, targetValue = 1f, label = "ring",
        animationSpec = infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing)),
    )
    val tint = MaterialTheme.colorScheme.primary
    Box(Modifier.padding(bottom = 20.dp).size(132.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(132.dp)) {
            drawCircle(
                color = tint.copy(alpha = 0.4f * (1f - ring)),
                radius = size.minDimension / 2f * (0.55f + 0.45f * ring),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
        Box(Modifier.size(72.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(34.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun AddButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(TerraIcons.Add, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
private fun SensorsStep(sensors: List<SensorInfo>, onAdd: () -> Unit) {
    Body("Añade los sensores conectados a la estación: cada uno ocupa un pin libre y hay sitio para seis. " +
        "También puedes hacerlo más tarde desde Periféricos.")
    if (sensors.isEmpty()) Hint("Todavía no hay sensores.")
    sensors.forEach { Hint("${sensorTypeLabel(it.type)} · GPIO ${it.gpio} · puerto ${it.port}") }
    AddButton("Añadir sensor", onAdd)
}

@Composable
private fun LoraStep(module: Peripheral?, onAdd: () -> Unit, onEdit: () -> Unit, onRemove: () -> Unit) {
    Body("Un módulo LoRaWAN (Wio-E5) sube las mediciones a la nube y recibe la hora sin cobertura móvil. " +
        "Añádelo si la estación lleva uno; podrás cambiar sus pines o quitarlo desde Conectividad.")
    if (module == null) {
        Hint("Todavía no hay módulos.")
        AddButton("Añadir módulo LoRa", onAdd)
    } else {
        ListItemsCard(items = listOf(module)) { _, m ->
            PeripheralRow(m, onConsole = {}, onSignalClick = {}, onEdit = onEdit, onRemove = onRemove)
        }
    }
}

@Composable
private fun PasswordStep(
    prov: Boolean,
    password: String,
    confirm: String,
    mismatch: Boolean,
    onPassword: (String) -> Unit,
    onConfirm: (String) -> Unit,
) {
    if (prov) {
        Body("La estación ya tiene contraseña. Puedes cambiarla desde Configurar.")
        return
    }
    Body("Protege la estación: solo quien conozca la contraseña podrá cambiar su configuración. " +
        "Déjala en blanco para dejarla abierta.")
    PasswordField(value = password, onValueChange = onPassword, label = "Contraseña (mín. 4)")
    PasswordField(
        value = confirm, onValueChange = onConfirm, label = "Repite la contraseña",
        isError = mismatch, supportingText = { if (mismatch) Text("No coinciden") },
    )
}
