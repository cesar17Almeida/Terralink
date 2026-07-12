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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.astralink.terralink.ble.protocol.ChannelPatch
import com.astralink.terralink.ble.protocol.PinmapMsg
import com.astralink.terralink.ble.protocol.SensorInfo
import com.astralink.terralink.ble.protocol.SensorPatch
import com.astralink.terralink.ui.components.BackIconButton
import com.astralink.terralink.ui.components.PicoPinout
import com.astralink.terralink.ui.components.PinCap
import com.astralink.terralink.ui.components.PinCell
import com.astralink.terralink.ui.components.PinLive
import com.astralink.terralink.ui.components.TerraIcons
import com.astralink.terralink.ui.components.TerraTextField
import com.astralink.terralink.ui.components.dismissKeyboardOnTap
import com.astralink.terralink.ui.components.mergePinmap
import com.astralink.terralink.ui.components.picoWHeader

// --- catalog ----------------------------------------------------------------

private data class SensorTypeInfo(val id: String, val label: String, val desc: String, val needCaps: Int)

private val SENSOR_TYPES = listOf(
    SensorTypeInfo("sdi12_aquacheck", "AquaCheck (SDI-12)",
        "Sonda de humedad sub-superficial, formato fijo", PinCap.PIO),
    SensorTypeInfo("sdi12_generic", "SDI-12 genérico",
        "Cualquier sensor SDI-12; etiqueta cada valor que devuelve", PinCap.PIO),
    SensorTypeInfo("analog_linear", "Analógico lineal",
        "Sensor analógico (ADC): valor = escala · crudo + offset", PinCap.ADC),
    SensorTypeInfo("onewire_ds18b20", "1-Wire DS18B20",
        "Termómetro digital que se autoidentifica (°C)", PinCap.PIO),
    SensorTypeInfo("dht11", "DHT11 (temp + humedad)",
        "Un pin digital; entrega temperatura y humedad del aire", PinCap.DIGITAL),
    SensorTypeInfo("hc_sr04", "HC-SR04 (distancia)",
        "Ultrasonidos; dos pines (trigger + echo); distancia en mm", PinCap.DIGITAL),
    SensorTypeInfo("actuator", "Actuador (salida ON/OFF)",
        "Un pin de salida digital; sin lecturas, control manual", PinCap.DIGITAL),
)

// Types that need a second pin (HC-SR04: gpio=trigger, gpio2=echo).
private fun needsSecondPin(type: String): Boolean = type == "hc_sr04"

private fun typeInfo(id: String): SensorTypeInfo =
    SENSOR_TYPES.firstOrNull { it.id == id } ?: SENSOR_TYPES.first()

internal fun sensorTypeLabel(id: String): String = typeInfo(id).label

private val READING_KINDS = listOf(
    "soil_moisture" to "Humedad de suelo",
    "soil_temperature" to "Temp. de suelo",
    "air_temperature" to "Temp. de aire",
    "air_humidity" to "Humedad de aire",
    "distance" to "Distancia",
    "generic" to "Genérico",
)

private fun kindLabel(id: String): String = READING_KINDS.firstOrNull { it.first == id }?.second ?: id

// Matches the firmware's SAVIA_CAPTURE_MIN_S..SAVIA_SLEEP_MAX_S for a per-sensor cadence.
private const val INTERVAL_MIN_S = 60
private const val INTERVAL_MAX_S = 86_400

// --- editable draft ---------------------------------------------------------

private data class ChannelDraft(val kind: String = "soil_moisture", val depthText: String = "")

private data class SensorDraft(
    val type: String = "sdi12_aquacheck",
    val gpio: Int? = null,
    val gpio2: Int? = null,                  // HC-SR04 echo pin
    val addr: String = "0",
    val followGlobal: Boolean = true,
    val intervalText: String = "",
    val kind: String = "soil_moisture",
    val depthText: String = "",
    val scaleText: String = "",
    val offsetText: String = "",
    val unitText: String = "",               // free unit label (analog / generic)
    val channels: List<ChannelDraft> = listOf(ChannelDraft()),
)

private fun draftFrom(s: SensorInfo): SensorDraft = SensorDraft(
    type = s.type,
    gpio = s.gpio,
    gpio2 = s.gpio2,
    addr = s.addr.ifBlank { "0" },
    followGlobal = s.intervalS <= 0,
    intervalText = if (s.intervalS > 0) s.intervalS.toString() else "",
    kind = s.kind ?: "soil_moisture",
    depthText = s.depthCm?.toString() ?: "",
    scaleText = s.scale?.toString() ?: "",
    offsetText = s.offset?.toString() ?: "",
    unitText = s.unit ?: "",
    channels = s.chan?.takeIf { it.isNotEmpty() }
        ?.map { ChannelDraft(it.kind, it.depthCm.toString()) }
        ?: listOf(ChannelDraft()),
)

// Max unit-label length (firmware slot->unit is <= 8 chars incl. NUL).
private const val UNIT_MAX = 8

// null when the draft isn't valid for its type (also gates the Guardar button).
private fun SensorDraft.toPatchOrNull(): SensorPatch? {
    val g = gpio ?: return null
    val interval: Int? = if (followGlobal) null
        else intervalText.trim().toIntOrNull()?.takeIf { it in INTERVAL_MIN_S..INTERVAL_MAX_S } ?: return null

    val unit = unitText.trim().take(UNIT_MAX).ifBlank { null }
    return when (type) {
        "sdi12_aquacheck" ->
            SensorPatch(gpio = g, type = type, addr = addr.ifBlank { "0" }, intervalS = interval)

        "sdi12_generic" -> {
            if (channels.isEmpty()) return null
            val chans = ArrayList<ChannelPatch>(channels.size)
            for (c in channels) {
                val d = c.depthText.trim().toIntOrNull() ?: return null
                chans.add(ChannelPatch(c.kind, d))
            }
            SensorPatch(gpio = g, type = type, addr = addr.ifBlank { "0" }, intervalS = interval,
                chan = chans, unit = unit)
        }

        "analog_linear" -> {
            val sc = scaleText.trim().toDoubleOrNull() ?: return null
            val off = offsetText.trim().ifBlank { "0" }.toDoubleOrNull() ?: return null
            val depth = depthText.trim().ifBlank { "0" }.toIntOrNull() ?: return null
            SensorPatch(gpio = g, type = type, intervalS = interval,
                kind = kind, depthCm = depth, scale = sc, offset = off, unit = unit)
        }

        "onewire_ds18b20" -> {
            val depth = depthText.trim().ifBlank { "0" }.toIntOrNull() ?: return null
            SensorPatch(gpio = g, type = type, intervalS = interval, kind = kind, depthCm = depth)
        }

        "dht11" ->                               // one digital pin; fixed outputs (air temp + humidity)
            SensorPatch(gpio = g, type = type, intervalS = interval)

        "hc_sr04" -> {                           // trigger = gpio, echo = gpio2 (both required)
            val echo = gpio2 ?: return null
            SensorPatch(gpio = g, type = type, gpio2 = echo, intervalS = interval, unit = unit)
        }

        "actuator" ->                            // one digital output; no readings
            SensorPatch(gpio = g, type = type)

        else -> null
    }
}

// --- pinout cells (device pinmap + free up the sensor being edited) ----------

private fun buildCells(pinmap: PinmapMsg?, freeGpios: Set<Int>, blocked: Int? = null): List<PinCell> {
    val header = picoWHeader()
    if (pinmap == null) return header
    val live = HashMap<Int, Triple<PinLive, String?, Int?>>()
    for (p in pinmap.pins) {
        if (p.gpio in freeGpios) continue   // editing this sensor -> its own pins are selectable again
        val state = when (p.state) {
            "in_use" -> PinLive.IN_USE
            "reserved" -> PinLive.RESERVED
            else -> PinLive.FREE
        }
        live[p.gpio] = Triple(state, p.reason.ifBlank { null }, p.port)
    }
    // A pin already chosen elsewhere in this wizard (the HC-SR04 trigger while picking
    // echo, or vice-versa) shows as taken so the same pin can't be assigned twice.
    if (blocked != null) live[blocked] = Triple(PinLive.IN_USE, "sensor", null)
    return mergePinmap(header, live)
}

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

/** What the wizard is editing: a brand-new sensor, or an existing one at [index]. */
sealed class SensorTarget {
    data object New : SensorTarget()
    data class Edit(val index: Int, val sensor: SensorInfo) : SensorTarget()
}

private const val STEP_COUNT = 4   // type -> pin -> mapping -> cadence

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SensorWizardScreen(
    target: SensorTarget,
    existing: List<SensorInfo>,
    pinmap: PinmapMsg?,
    busy: Boolean,
    error: String?,
    onCancel: () -> Unit,
    onSave: (List<SensorPatch>) -> Unit,
) {
    var step by remember { mutableStateOf(0) }
    var draft by remember {
        mutableStateOf(when (target) {
            is SensorTarget.Edit -> draftFrom(target.sensor)
            SensorTarget.New -> SensorDraft()
        })
    }

    val editIndex = (target as? SensorTarget.Edit)?.index
    // The sensor being edited frees its own pin(s) so they're selectable again.
    val editPins: Set<Int> = remember(target) {
        (target as? SensorTarget.Edit)?.sensor?.let { setOfNotNull(it.gpio, it.gpio2) } ?: emptySet()
    }
    val patch = draft.toPatchOrNull()

    // Build the full sensor table to send: existing sensors (as patches), with this
    // draft inserted (New) or replacing slot editIndex.
    fun buildTable(p: SensorPatch): List<SensorPatch> {
        val base = existing.mapIndexed { i, s -> if (i == editIndex) p else s.toPatch() }
        return if (editIndex == null) base + p else base
    }

    val canNext = when (step) {
        1 -> draft.gpio != null && (!needsSecondPin(draft.type) || draft.gpio2 != null)
        else -> true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (target is SensorTarget.Edit) "Editar sensor" else "Añadir sensor",
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = { BackIconButton(onClick = onCancel) },
                actions = { TextButton(onClick = onCancel) { Text("Cancelar") } },
            )
        },
        bottomBar = {
            WizardBar(
                step = step,
                busy = busy,
                canNext = canNext,
                canSave = patch != null,
                onBack = { if (step == 0) onCancel() else step-- },
                onNext = { if (step < STEP_COUNT - 1) step++ },
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
            StepHeader(step)
            when (step) {
                0 -> TypeStep(draft) { draft = draft.copy(type = it, gpio = null, gpio2 = null) }
                1 -> PinStep(
                    draft = draft,
                    pinmap = pinmap,
                    editPins = editPins,
                    onSelectTrigger = { draft = draft.copy(gpio = it) },
                    onSelectEcho = { draft = draft.copy(gpio2 = it) },
                )
                2 -> MappingStep(draft) { draft = it }
                3 -> CadenceStep(draft) { draft = it }
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun StepHeader(step: Int) {
    val title = when (step) {
        0 -> "1 · Tipo de sensor"
        1 -> "2 · Pin de conexión"
        2 -> "3 · Valores"
        else -> "4 · Cadencia de muestreo"
    }
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Paso ${step + 1} de $STEP_COUNT", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TypeStep(draft: SensorDraft, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SENSOR_TYPES.forEach { t ->
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

@Composable
private fun PinStep(
    draft: SensorDraft,
    pinmap: PinmapMsg?,
    editPins: Set<Int>,
    onSelectTrigger: (Int) -> Unit,
    onSelectEcho: (Int) -> Unit,
) {
    val info = typeInfo(draft.type)
    val capName = if (info.needCaps == PinCap.ADC) "analógico (ADC, GP26–28)" else "digital"
    val two = needsSecondPin(draft.type)
    // Each picker blocks the pin the OTHER already holds so a pin can't be assigned twice.
    val triggerCells = remember(pinmap, editPins, draft.gpio2) { buildCells(pinmap, editPins, blocked = draft.gpio2) }
    val echoCells = remember(pinmap, editPins, draft.gpio) { buildCells(pinmap, editPins, blocked = draft.gpio) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (two) "El HC-SR04 usa dos pines: trigger (salida) y echo (entrada). Toca un pin para cada uno."
            else "Toca un pin resaltado. ${info.label} necesita un pin $capName.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PinPicker(
            title = if (two) "Trigger (salida)" else null,
            selected = draft.gpio,
            cells = triggerCells,
            needCaps = info.needCaps,
            onSelect = onSelectTrigger,
        )
        if (two) {
            PinPicker(
                title = "Echo (entrada)",
                selected = draft.gpio2,
                cells = echoCells,
                needCaps = info.needCaps,
                onSelect = onSelectEcho,
            )
        }
    }
}

@Composable
private fun PinPicker(
    title: String?,
    selected: Int?,
    cells: List<PinCell>,
    needCaps: Int,
    onSelect: (Int) -> Unit,
) {
    if (title != null) {
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
    }
    Text(
        selected?.let { "Seleccionado: GPIO $it" } ?: "Ningún pin seleccionado",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        color = if (selected != null) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        PicoPinout(
            cells = cells,
            needCaps = needCaps,
            onSelect = onSelect,
            modifier = Modifier.fillMaxWidth(0.66f),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MappingStep(draft: SensorDraft, onChange: (SensorDraft) -> Unit) {
    when (draft.type) {
        "sdi12_aquacheck" -> AddrField(draft, onChange) {
            Text(
                "La AquaCheck tiene un formato fijo (humedad por profundidad); sólo necesita su dirección SDI-12.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        "sdi12_generic" -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AddrField(draft, onChange, supporting = null)
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

        "actuator" -> InfoStep(
            "Salida digital ON/OFF; no produce lecturas. Una vez guardado, contrólalo con el " +
                "interruptor que aparece en la lista de sensores.",
        )
    }
}

@Composable
private fun UnitField(draft: SensorDraft, onChange: (SensorDraft) -> Unit) {
    TerraTextField(
        value = draft.unitText,
        onValueChange = { onChange(draft.copy(unitText = it.take(UNIT_MAX))) },
        label = "Unidad (opcional)",
        supportingText = { Text("Etiqueta libre para el valor (p. ej. mm, %, ppm). Máx. $UNIT_MAX.") },
    )
}

@Composable
private fun InfoStep(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun WarningCard(text: String) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(
                TerraIcons.Bolt, contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
private fun AddrField(
    draft: SensorDraft,
    onChange: (SensorDraft) -> Unit,
    supporting: (@Composable () -> Unit)?,
) {
    TerraTextField(
        value = draft.addr,
        onValueChange = { onChange(draft.copy(addr = it.take(1))) },
        label = "Dirección SDI-12",
        supportingText = supporting ?: { Text("Normalmente '0'") },
    )
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun KindPicker(draft: SensorDraft, onChange: (SensorDraft) -> Unit) {
    Column {
        Text("Magnitud", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            READING_KINDS.forEach { (id, label) ->
                FilterChip(selected = draft.kind == id, onClick = { onChange(draft.copy(kind = id)) },
                    label = { Text(label) })
            }
        }
    }
}

@Composable
private fun DepthField(draft: SensorDraft, onChange: (SensorDraft) -> Unit) {
    TerraTextField(
        value = draft.depthText,
        onValueChange = { onChange(draft.copy(depthText = it.filter { c -> c.isDigit() })) },
        label = "Profundidad (cm)",
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        supportingText = { Text("0 para aire / superficie") },
    )
}

@Composable
private fun ChannelsEditor(draft: SensorDraft, onChange: (SensorDraft) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Canales (un valor devuelto por el sensor)", style = MaterialTheme.typography.bodyMedium)
        draft.channels.forEachIndexed { i, ch ->
            Card(shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Canal ${i + 1}", style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f))
                        if (draft.channels.size > 1) {
                            IconButton(onClick = {
                                onChange(draft.copy(channels = draft.channels.filterIndexed { j, _ -> j != i }))
                            }) {
                                Icon(TerraIcons.Delete, contentDescription = "Quitar canal",
                                    modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    ChannelKindChips(ch.kind) { k ->
                        onChange(draft.copy(channels = draft.channels.mapIndexed { j, c ->
                            if (j == i) c.copy(kind = k) else c
                        }))
                    }
                    TerraTextField(
                        value = ch.depthText,
                        onValueChange = { v ->
                            onChange(draft.copy(channels = draft.channels.mapIndexed { j, c ->
                                if (j == i) c.copy(depthText = v.filter { d -> d.isDigit() }) else c
                            }))
                        },
                        label = "Profundidad (cm)",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = ch.depthText.isNotBlank() && ch.depthText.trim().toIntOrNull() == null,
                    )
                }
            }
        }
        if (draft.channels.size < 4) {
            OutlinedButton(
                onClick = { onChange(draft.copy(channels = draft.channels + ChannelDraft())) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(TerraIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Añadir canal")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ChannelKindChips(selected: String, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        READING_KINDS.forEach { (id, label) ->
            FilterChip(selected = selected == id, onClick = { onPick(id) }, label = { Text(label) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun CadenceStep(draft: SensorDraft, onChange: (SensorDraft) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Cada cuánto se lee este sensor. Por defecto sigue la cadencia global de captura de la estación.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = draft.followGlobal, onClick = { onChange(draft.copy(followGlobal = true)) },
                label = { Text("Cadencia global") })
            FilterChip(selected = !draft.followGlobal, onClick = { onChange(draft.copy(followGlobal = false)) },
                label = { Text("Personalizada") })
        }
        if (!draft.followGlobal) {
            val v = draft.intervalText.trim().toIntOrNull()
            val valid = v != null && v in INTERVAL_MIN_S..INTERVAL_MAX_S
            TerraTextField(
                value = draft.intervalText,
                onValueChange = { onChange(draft.copy(intervalText = it.filter { c -> c.isDigit() })) },
                label = "Intervalo (segundos)",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = draft.intervalText.isNotBlank() && !valid,
                supportingText = {
                    if (draft.intervalText.isNotBlank() && !valid) {
                        Text("Entre $INTERVAL_MIN_S s y $INTERVAL_MAX_S s (24 h)")
                    } else {
                        Text(v?.let { "= ${intervalHuman(it)}" } ?: "")
                    }
                },
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(60 to "1 min", 300 to "5 min", 1800 to "30 min", 3600 to "1 h").forEach { (s, l) ->
                    FilterChip(selected = v == s, onClick = { onChange(draft.copy(intervalText = s.toString())) },
                        label = { Text(l) })
                }
            }
        }
    }
}

@Composable
private fun WizardBar(
    step: Int,
    busy: Boolean,
    canNext: Boolean,
    canSave: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onSave: () -> Unit,
) {
    val last = step == STEP_COUNT - 1
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = onBack, enabled = !busy, modifier = Modifier.weight(1f)) {
            Text(if (step == 0) "Cancelar" else "Atrás")
        }
        if (last) {
            Button(onClick = onSave, enabled = canSave && !busy, modifier = Modifier.weight(1f)) {
                Text(if (busy) "Guardando…" else "Guardar")
            }
        } else {
            Button(onClick = onNext, enabled = canNext && !busy, modifier = Modifier.weight(1f)) {
                Text("Siguiente")
            }
        }
    }
}

internal fun intervalHuman(s: Int): String = when {
    s % 3600 == 0 && s >= 3600 -> "${s / 3600} h"
    s % 60 == 0 && s >= 60 -> "${s / 60} min"
    else -> "$s s"
}

/** Full-table patch with the sensor at [index] removed (delete = re-send the rest). */
internal fun sensorTableWithout(sensors: List<SensorInfo>, index: Int): List<SensorPatch> =
    sensors.filterIndexed { i, _ -> i != index }.map { it.toPatch() }

// SensorInfo -> SensorPatch (re-send an unchanged sensor verbatim in a full-table patch).
private fun SensorInfo.toPatch(): SensorPatch = SensorPatch(
    gpio = gpio,
    type = type,
    addr = addr.takeIf { type.startsWith("sdi12") && it.isNotBlank() },
    intervalS = intervalS.takeIf { it > 0 },
    kind = kind?.takeIf { type == "analog_linear" || type == "onewire_ds18b20" },
    depthCm = depthCm?.takeIf { type == "analog_linear" || type == "onewire_ds18b20" },
    scale = scale?.takeIf { type == "analog_linear" },
    offset = offset?.takeIf { type == "analog_linear" },
    chan = chan?.takeIf { type == "sdi12_generic" }?.map { ChannelPatch(it.kind, it.depthCm) },
    gpio2 = gpio2?.takeIf { type == "hc_sr04" },
    unit = unit,
)
