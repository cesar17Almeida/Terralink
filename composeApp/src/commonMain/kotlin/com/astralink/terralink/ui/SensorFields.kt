// Everything the sensor screens share: the type catalog, the editable draft and its
// validation, and the per-field composables. The wizard walks them one step at a
// time and the edit screen shows them all at once -- same fields, same rules.
package com.astralink.terralink.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.astralink.terralink.ble.protocol.ChannelPatch
import com.astralink.terralink.ble.protocol.MAX_SENSOR_SLOTS
import com.astralink.terralink.ble.protocol.PinmapMsg
import com.astralink.terralink.ble.protocol.SensorInfo
import com.astralink.terralink.ble.protocol.SensorPatch
import com.astralink.terralink.ui.components.PinCap
import com.astralink.terralink.ui.components.PinSlot
import com.astralink.terralink.ui.components.SensorPinPicker
import com.astralink.terralink.ui.components.Sdi12AddressField
import com.astralink.terralink.ui.components.TerraIcons
import com.astralink.terralink.ui.components.TerraTextField
import com.astralink.terralink.ui.components.isValidSdi12Address

// --- catalog ----------------------------------------------------------------

// `output` splits the catalog in two, the same way the firmware's sensor catalog
// does: inputs measure and store readings on a cadence, outputs are only driven.
internal data class SensorTypeInfo(
    val id: String,
    val label: String,
    val desc: String,
    val needCaps: Int,
    val output: Boolean = false,
)

internal val SENSOR_TYPES = listOf(
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
    SensorTypeInfo("actuator", "Salida digital",
        "Un pin digital que la app enciende y apaga (relé, válvula); no genera lecturas",
        PinCap.DIGITAL, output = true),
)

// Types that need a second pin (HC-SR04: gpio=trigger, gpio2=echo).
internal fun needsSecondPin(type: String): Boolean = type == "hc_sr04"

internal fun typeInfo(id: String): SensorTypeInfo =
    SENSOR_TYPES.firstOrNull { it.id == id } ?: SENSOR_TYPES.first()

internal fun sensorTypeLabel(id: String): String = typeInfo(id).label

/** True for output-only types: they are driven, never sampled. */
internal fun isOutputType(id: String): Boolean = typeInfo(id).output

internal val READING_KINDS = listOf(
    "soil_moisture" to "Humedad de suelo",
    "soil_temperature" to "Temp. de suelo",
    "air_temperature" to "Temp. de aire",
    "air_humidity" to "Humedad de aire",
    "distance" to "Distancia",
    "generic" to "Genérico",
)

internal fun kindLabel(id: String): String = READING_KINDS.firstOrNull { it.first == id }?.second ?: id

// Matches the firmware's SAVIA_CAPTURE_MIN_S..SAVIA_SLEEP_MAX_S for a per-sensor cadence.
internal const val INTERVAL_MIN_S = 60
internal const val INTERVAL_MAX_S = 86_400

// --- editable draft ---------------------------------------------------------

internal data class ChannelDraft(val kind: String = "soil_moisture", val depthText: String = "")

internal data class SensorDraft(
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

internal fun draftFrom(s: SensorInfo): SensorDraft = SensorDraft(
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
internal const val UNIT_MAX = 8

// null when the draft isn't valid for its type (also gates the Guardar button).
// `port` is the slot the sensor occupies -- kept across an edit, lowest free one
// for a new sensor -- and travels with the patch so the station never has to infer
// it from array order (see SensorPatch).
internal fun SensorDraft.toPatchOrNull(port: Int): SensorPatch? {
    val g = gpio ?: return null
    // Outputs skip this outright: a stale interval_s on an actuator slot must not
    // block the save through a field the wizard no longer shows.
    val interval: Int? = if (followGlobal || isOutputType(type)) null
        else intervalText.trim().toIntOrNull()?.takeIf { it in INTERVAL_MIN_S..INTERVAL_MAX_S } ?: return null

    val unit = unitText.trim().take(UNIT_MAX).ifBlank { null }
    // Blank means "not touched yet" -> the factory default. Anything the protocol
    // can't put on the wire blocks the save instead of failing on the first read.
    val sdi12Addr = addr.ifBlank { "0" }
    if (type.startsWith("sdi12") && !isValidSdi12Address(sdi12Addr)) return null

    return when (type) {
        "sdi12_aquacheck" ->
            SensorPatch(port = port, gpio = g, type = type, addr = sdi12Addr, intervalS = interval)

        "sdi12_generic" -> {
            if (channels.isEmpty()) return null
            val chans = ArrayList<ChannelPatch>(channels.size)
            for (c in channels) {
                val d = c.depthText.trim().toIntOrNull() ?: return null
                chans.add(ChannelPatch(c.kind, d))
            }
            SensorPatch(port = port, gpio = g, type = type, addr = sdi12Addr, intervalS = interval,
                chan = chans, unit = unit)
        }

        "analog_linear" -> {
            val sc = scaleText.trim().toDoubleOrNull() ?: return null
            val off = offsetText.trim().ifBlank { "0" }.toDoubleOrNull() ?: return null
            val depth = depthText.trim().ifBlank { "0" }.toIntOrNull() ?: return null
            SensorPatch(port = port, gpio = g, type = type, intervalS = interval,
                kind = kind, depthCm = depth, scale = sc, offset = off, unit = unit)
        }

        "onewire_ds18b20" -> {
            val depth = depthText.trim().ifBlank { "0" }.toIntOrNull() ?: return null
            SensorPatch(port = port, gpio = g, type = type, intervalS = interval, kind = kind, depthCm = depth)
        }

        "dht11" ->                               // one digital pin; fixed outputs (air temp + humidity)
            SensorPatch(port = port, gpio = g, type = type, intervalS = interval)

        "hc_sr04" -> {                           // trigger = gpio, echo = gpio2 (both required)
            val echo = gpio2 ?: return null
            SensorPatch(port = port, gpio = g, type = type, gpio2 = echo, intervalS = interval, unit = unit)
        }

        "actuator" ->                            // one digital output; no readings
            SensorPatch(port = port, gpio = g, type = type)

        else -> null
    }
}

/** First type of the asked-for kind: what picking a kind seeds the draft with. */
internal fun defaultTypeFor(output: Boolean): String =
    SENSOR_TYPES.first { it.output == output }.id

/** Label/value rows in a muted card: what is about to be written. */
@Composable
internal fun SummaryCard(rows: List<Pair<String, String>>) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rows.forEach { (label, value) ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(label, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f))
                    Text(value, style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
internal fun UnitField(draft: SensorDraft, onChange: (SensorDraft) -> Unit) {
    TerraTextField(
        value = draft.unitText,
        onValueChange = { onChange(draft.copy(unitText = it.take(UNIT_MAX))) },
        label = "Unidad (opcional)",
        supportingText = { Text("Etiqueta libre para el valor (p. ej. mm, %, ppm). Máx. $UNIT_MAX.") },
    )
}

@Composable
internal fun InfoStep(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun WarningCard(text: String) {
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
internal fun AddrField(
    draft: SensorDraft,
    onProbe: (suspend (gpio: Int) -> String?)?,
    onChange: (SensorDraft) -> Unit,
    supporting: (@Composable () -> Unit)?,
) {
    Sdi12AddressField(
        value = draft.addr,
        onValueChange = { onChange(draft.copy(addr = it)) },
        gpio = draft.gpio,
        onProbe = onProbe,
        supporting = supporting,
    )
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun KindPicker(draft: SensorDraft, onChange: (SensorDraft) -> Unit) {
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
internal fun DepthField(draft: SensorDraft, onChange: (SensorDraft) -> Unit) {
    TerraTextField(
        value = draft.depthText,
        onValueChange = { onChange(draft.copy(depthText = it.filter { c -> c.isDigit() })) },
        label = "Profundidad (cm)",
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        supportingText = { Text("0 para aire / superficie") },
    )
}

@Composable
internal fun ChannelsEditor(draft: SensorDraft, onChange: (SensorDraft) -> Unit) {
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
internal fun ChannelKindChips(selected: String, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        READING_KINDS.forEach { (id, label) ->
            FilterChip(selected = selected == id, onClick = { onPick(id) }, label = { Text(label) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun CadenceFields(draft: SensorDraft, onChange: (SensorDraft) -> Unit) {
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

internal fun intervalHuman(s: Int): String = when {
    s % 3600 == 0 && s >= 3600 -> "${s / 3600} h"
    s % 60 == 0 && s >= 60 -> "${s / 60} min"
    else -> "$s s"
}

/**
 * Full-table patch with the sensor at [index] removed (delete = re-send the rest).
 * The survivors keep the ports they already had -- the deleted one just leaves a
 * hole. Compacting here would renumber them, and every stored reading is keyed by
 * port, so the next sensor to reuse that number would inherit a stranger's history.
 */
internal fun sensorTableWithout(sensors: List<SensorInfo>, index: Int): List<SensorPatch> =
    sensors.filterIndexed { i, _ -> i != index }.map { it.toPatch() }

/**
 * Full-table patch with slot [index] swapped for [patch] (an edit re-sends the whole
 * table with one slot changed). Every other sensor goes back verbatim, port included,
 * so editing one never disturbs the rest.
 */
internal fun sensorTableWith(
    sensors: List<SensorInfo>,
    index: Int,
    patch: SensorPatch,
): List<SensorPatch> = sensors.mapIndexed { i, s -> if (i == index) patch else s.toPatch() }

/** Lowest free slot for a new sensor, or null when all [MAX_SENSOR_SLOTS] are taken. */
internal fun firstFreePort(sensors: List<SensorInfo>): Int? =
    (1..MAX_SENSOR_SLOTS).firstOrNull { p -> sensors.none { it.port == p } }

// SensorInfo -> SensorPatch (re-send an unchanged sensor verbatim in a full-table
// patch). Carries its own port, so re-sending the table never moves a sensor.
internal fun SensorInfo.toPatch(): SensorPatch = SensorPatch(
    port = port,
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

/** Pin capabilities a type needs, for the pin picker (see [PinCap]). */
internal fun sensorNeedCaps(type: String): Int = typeInfo(type).needCaps

/** One-line description of a type, under its label. */
internal fun sensorTypeDesc(type: String): String = typeInfo(type).desc

/** Section that assigns the sensor's pin(s): the board header in PICK mode, with
 *  the caption saying what this type needs from a pin. */
@Composable
internal fun PinField(
    draft: SensorDraft,
    pinmap: PinmapMsg?,
    pinmapWarning: String?,
    editPins: Set<Int>,
    port: Int?,
    editing: Boolean,
    onSelectTrigger: (Int) -> Unit,
    onSelectEcho: (Int) -> Unit,
) {
    val info = typeInfo(draft.type)
    val capName = if (info.needCaps == PinCap.ADC) "analógico (ADC, GP26–28)" else "digital"
    val two = needsSecondPin(draft.type)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Without the inventory every pin is drawn free, so say so up front instead
        // of letting the user pick one the station will refuse.
        pinmapWarning?.let { WarningCard(it) }
        Text(
            if (two) "El HC-SR04 usa dos pines: trigger (salida) y echo (entrada). Elige la ranura y toca su pin."
            else if (info.output) "Toca un pin resaltado. El actuador lo usará como salida digital."
            else "Toca un pin resaltado. ${info.label} necesita un pin $capName.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Moving a sensor to another pin is safe and needs no warning: the port --
        // which is what every stored reading is keyed by -- does not change.
        if (editing) {
            Text(
                "Cambiar de pin no afecta al histórico: el sensor sigue en el puerto $port.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SensorPinPicker(
            pinmap = pinmap,
            freePins = editPins,
            needCaps = info.needCaps,
            twoPins = two,
            gpio = draft.gpio,
            gpio2 = draft.gpio2,
            onSelect = { slot, g ->
                if (slot == PinSlot.TRIGGER) onSelectTrigger(g) else onSelectEcho(g)
            },
        )
    }
}
