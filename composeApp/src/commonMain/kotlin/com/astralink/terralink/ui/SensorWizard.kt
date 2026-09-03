package com.astralink.terralink.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.astralink.terralink.ble.protocol.PinmapMsg
import com.astralink.terralink.ble.protocol.SensorInfo
import com.astralink.terralink.ble.protocol.SensorPatch
import com.astralink.terralink.ui.components.BackIconButton
import com.astralink.terralink.ui.components.TerraIcons
import com.astralink.terralink.ui.components.TerraTextField
import com.astralink.terralink.ui.components.dismissKeyboardOnTap


// --- sensor list row (shown in SensorsScreen) -------------------------------

@Composable
internal fun SensorRow(
    index: Int,
    sensor: SensorInfo,
    busy: Boolean,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // Tapping the row body opens this sensor's history; the pencil edits it.
        Column(modifier = Modifier.weight(1f).clickable(enabled = !busy, onClick = onOpen)) {
            Text(
                "${sensorTypeLabel(sensor.type)} · GPIO ${sensor.gpio}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                buildString {
                    append("Puerto ${sensor.port}")
                    if (sensor.type.startsWith("sdi12") && sensor.addr.isNotBlank()) append(" · addr ${sensor.addr}")
                    sensor.gpio2?.let { append(" · echo GPIO $it") }
                    sensor.unit?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                    append(" · ")
                    append(if (sensor.intervalS > 0) "cada ${intervalHuman(sensor.intervalS)}" else "cadencia global")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onEdit, enabled = !busy) {
            Icon(TerraIcons.Edit, contentDescription = "Editar", modifier = Modifier.size(20.dp))
        }
        IconButton(onClick = onDelete, enabled = !busy) {
            Icon(TerraIcons.Delete, contentDescription = "Eliminar", modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.error)
        }
    }
}

// --- the wizard -------------------------------------------------------------

// The wizard's steps. WHICH of them a device walks through is a property of its
// type, not a constant: an output has no sampling cadence, so an actuator goes
// kind -> type -> pin -> summary and is never asked how often to read a pin it writes.
private enum class WizStep { KIND, TYPE, PIN, MAPPING, CADENCE }

private fun stepsFor(type: String, pickKind: Boolean): List<WizStep> = buildList {
    if (pickKind) add(WizStep.KIND)
    add(WizStep.TYPE)
    add(WizStep.PIN)
    add(WizStep.MAPPING)
    if (!isOutputType(type)) add(WizStep.CADENCE)
}


private fun stepTitle(step: WizStep, type: String): String = when (step) {
    WizStep.KIND -> "¿Qué vas a añadir?"
    WizStep.TYPE -> if (isOutputType(type)) "Tipo de actuador" else "Tipo de sensor"
    WizStep.PIN -> "Pin de conexión"
    WizStep.MAPPING -> if (isOutputType(type)) "Resumen" else "Valores"
    WizStep.CADENCE -> "Cadencia de muestreo"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SensorWizardScreen(
    // true = opened from the FAB, so it starts on the sensor-vs-actuator fork; false =
    // the caller already knows what it is adding and [initialType] seeds the catalogue.
    pickKind: Boolean,
    existing: List<SensorInfo>,
    pinmap: PinmapMsg?,
    pinmapWarning: String?,          // set when the station's pin inventory couldn't be read
    busy: Boolean,
    error: String?,
    onCancel: () -> Unit,
    onSave: (List<SensorPatch>) -> Unit,
    // One `?!` round trip on the chosen pin, so the wizard can read the SDI-12
    // address off the probe instead of asking for it. null = no live station.
    onProbeSdi12Address: (suspend (gpio: Int) -> String?)? = null,
    initialType: String = "sdi12_aquacheck",
) {
    var step by remember { mutableStateOf(0) }
    // The fork starts unanswered: the draft has to carry SOME type, so without this
    // the sensor box would render pre-picked and "Siguiente" would answer for the user.
    var kindPicked by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(SensorDraft(type = initialType)) }

    // The slot the new sensor takes: the lowest free one. null = every slot is taken,
    // so there is nothing to save.
    val slotPort = remember(existing) { firstFreePort(existing) }
    val patch = slotPort?.let { draft.toPatchOrNull(it) }

    // The firmware swaps the whole table, so the draft is appended to the ones already
    // configured -- each re-sent verbatim, keeping its own port.
    fun buildTable(p: SensorPatch): List<SensorPatch> = existing.map { it.toPatch() } + p

    // The flow's length depends on the chosen type, and only the CADENCE step at the
    // end comes and goes with it -- every step before it keeps its index. Clamped
    // anyway: an index that outlives its list is not worth a crash.
    val steps = stepsFor(draft.type, pickKind)
    val stepIdx = step.coerceIn(0, steps.lastIndex)
    val canNext = when (steps[stepIdx]) {
        WizStep.KIND -> kindPicked
        WizStep.PIN -> draft.gpio != null && (!needsSecondPin(draft.type) || draft.gpio2 != null)
        else -> true
    }
    val noun = if (isOutputType(draft.type)) "actuador" else "sensor"

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        // Nothing has been chosen yet on the fork itself.
                        if (steps[stepIdx] == WizStep.KIND) "Añadir dispositivo" else "Añadir $noun",
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = { BackIconButton(onClick = onCancel) },
                actions = { TextButton(onClick = onCancel) { Text("Cancelar") } },
            )
        },
        bottomBar = {
            WizardBar(
                lastStep = stepIdx == steps.lastIndex,
                firstStep = stepIdx == 0,
                busy = busy,
                canNext = canNext,
                canSave = patch != null,
                onBack = { if (stepIdx == 0) onCancel() else step = stepIdx - 1 },
                onNext = { if (stepIdx < steps.lastIndex) step = stepIdx + 1 },
                onSave = { patch?.let { onSave(buildTable(it)) } },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .dismissKeyboardOnTap()       // tap outside a field to hide the keyboard
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StepHeader(stepIdx, steps, draft.type)
            when (steps[stepIdx]) {
                // Picking a kind moves on by itself: it is a fork, not a form field.
                // Re-picking the same one keeps whatever the draft already had.
                WizStep.KIND -> KindStep(draft, picked = kindPicked) { output ->
                    if (output != isOutputType(draft.type)) draft = SensorDraft(type = defaultTypeFor(output))
                    kindPicked = true
                    step = stepIdx + 1
                }
                WizStep.TYPE -> TypeStep(draft) { draft = draft.copy(type = it, gpio = null, gpio2 = null) }
                WizStep.PIN -> PinField(
                    draft = draft,
                    pinmap = pinmap,
                    pinmapWarning = pinmapWarning,
                    editPins = emptySet(),
                    port = slotPort,
                    editing = false,
                    onSelectTrigger = { draft = draft.copy(gpio = it) },
                    onSelectEcho = { draft = draft.copy(gpio2 = it) },
                )
                WizStep.MAPPING -> MappingStep(draft, slotPort, onProbeSdi12Address) { draft = it }
                WizStep.CADENCE -> CadenceFields(draft) { draft = it }
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun StepHeader(index: Int, steps: List<WizStep>, type: String) {
    Column {
        Text(
            "${index + 1} · ${stepTitle(steps[index], type)}",
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
        )
        Text("Paso ${index + 1} de ${steps.size}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// The fork everything else hangs on, asked before anything else: a sensor is sampled
// on a cadence and keeps history, an actuator is only driven. Two boxes, no more.
@Composable
private fun KindStep(draft: SensorDraft, picked: Boolean, onPick: (output: Boolean) -> Unit) {
    val output = isOutputType(draft.type)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            "Un sensor mide; un actuador se enciende y apaga desde la app.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            KindCard(TerraIcons.Sensors, "Sensor", selected = picked && !output,
                modifier = Modifier.weight(1f)) { onPick(false) }
            KindCard(TerraIcons.ToggleOn, "Actuador", selected = picked && output,
                modifier = Modifier.weight(1f)) { onPick(true) }
        }
    }
}

/** One of the two boxes on the fork: a large icon over its word, nothing else. */
@Composable
private fun KindCard(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = modifier.aspectRatio(1f).clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) scheme.primaryContainer
            else scheme.surfaceVariant.copy(alpha = 0.4f),
        ),
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) scheme.primary else scheme.outlineVariant,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                icon, contentDescription = null, modifier = Modifier.size(44.dp),
                tint = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
            )
        }
    }
}

// Only the catalogue of the kind already chosen: the sensor/actuator split is its
// own step now, so nothing here has to be labelled twice.
@Composable
private fun TypeStep(draft: SensorDraft, onPick: (String) -> Unit) {
    val output = isOutputType(draft.type)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SENSOR_TYPES.filter { it.output == output }.forEach { t ->
            val selected = draft.type == t.id
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onPick(t.id) },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(t.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(2.dp))
                    Text(t.desc, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MappingStep(
    draft: SensorDraft,
    port: Int?,
    onProbe: (suspend (gpio: Int) -> String?)?,
    onChange: (SensorDraft) -> Unit,
) {
    when (draft.type) {
        "sdi12_aquacheck" -> AddrField(draft, onProbe, onChange) {
            Text(
                "Formato fijo por profundidad; solo necesita la dirección SDI-12.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        "sdi12_generic" -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AddrField(draft, onProbe, onChange, supporting = null)
            ChannelsEditor(draft, onChange)
        }

        "analog_linear" -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            KindPicker(draft, onChange)
            DepthField(draft, onChange)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TerraTextField(
                    value = draft.scaleText,
                    onValueChange = { onChange(draft.copy(scaleText = it)) },
                    label = "Escala",
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = draft.scaleText.isNotBlank() && draft.scaleText.trim().toDoubleOrNull() == null,
                )
                TerraTextField(
                    value = draft.offsetText,
                    onValueChange = { onChange(draft.copy(offsetText = it)) },
                    label = "Offset",
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
            Text(
                "valor = escala · crudo + offset (el offset por defecto es 0).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            UnitField(draft, onChange)
        }

        "onewire_ds18b20" -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            KindPicker(draft, onChange)
            DepthField(draft, onChange)
            Text(
                "El DS18B20 entrega °C directamente; elige qué representa esa temperatura.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        "dht11" -> InfoStep(
            "El DHT11 entrega temperatura y humedad del aire por un solo pin digital. " +
                "No requiere más ajustes; sólo la cadencia en el siguiente paso.",
        )

        "hc_sr04" -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoStep(
                "El HC-SR04 mide distancia (mm) con un pulso de trigger y midiendo el echo. " +
                    "Sin más ajustes; sólo la cadencia en el siguiente paso.",
            )
            WarningCard(
                "El HC-SR04 clásico funciona a 5 V: su pin ECHO puede dañar la Pico (3.3 V). " +
                    "Usa un divisor de tensión en ECHO o una variante de 3.3 V.",
            )
            UnitField(draft, onChange)
        }

        // An output has nothing to map: this last step confirms what is about to be
        // written instead of asking for a decoding the type does not have.
        "actuator" -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoStep(
                "Un actuador no se muestrea: no tiene cadencia ni histórico. La estación " +
                    "sólo lo conmuta cuando se lo pides desde la app.",
            )
            SummaryCard(
                listOf(
                    "Puerto" to (port?.toString() ?: "—"),
                    "Pin" to (draft.gpio?.let { "GP$it" } ?: "sin elegir"),
                    "Al arrancar" to "apagado",
                ),
            )
            Text(
                "Al guardar aparecerá en la sección Actuadores con su interruptor.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}


/** Back / next / save bar shared by the wizards; `saveLabel` names the last step's action. */
@Composable
internal fun WizardBar(
    lastStep: Boolean,
    firstStep: Boolean,
    busy: Boolean,
    canNext: Boolean,
    canSave: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onSave: () -> Unit,
    backLabel: String? = null,        // null = "Cancelar" on the first step, "Atrás" after
    nextLabel: String = "Siguiente",
    saveLabel: String = "Guardar",
) {
    val last = lastStep
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()      // edge-to-edge: clear of the gesture bar
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = onBack, enabled = !busy, modifier = Modifier.weight(1f)) {
            Text(backLabel ?: if (firstStep) "Cancelar" else "Atrás")
        }
        if (last) {
            Button(onClick = onSave, enabled = canSave && !busy, modifier = Modifier.weight(1f)) {
                Text(if (busy) "Guardando…" else saveLabel)
            }
        } else {
            Button(onClick = onNext, enabled = canNext && !busy, modifier = Modifier.weight(1f)) {
                Text(nextLabel)
            }
        }
    }
}

