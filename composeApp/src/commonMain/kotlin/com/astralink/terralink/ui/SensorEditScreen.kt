// Editing an existing sensor is not the same job as creating one: the installer
// comes here to change ONE thing (a pin, an address, the cadence), so everything is
// on a single screen and Guardar only lights up once something actually changed.
// Creating still walks the wizard, which has to ask in order because nothing exists
// yet. The pin picker is the one field too tall to inline -- it gets its own screen.
package com.astralink.terralink.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.astralink.terralink.ble.protocol.PinmapMsg
import com.astralink.terralink.ble.protocol.SensorInfo
import com.astralink.terralink.ble.protocol.SensorPatch
import com.astralink.terralink.ui.components.BackIconButton
import com.astralink.terralink.ui.components.SectionHeader
import com.astralink.terralink.ui.components.SettingsGroup
import com.astralink.terralink.ui.components.SettingsRowSpec
import com.astralink.terralink.ui.components.TerraDialog
import com.astralink.terralink.ui.components.TerraIcons
import com.astralink.terralink.ui.components.TerraTextField
import com.astralink.terralink.ui.components.dismissKeyboardOnTap

/**
 * @param index slot index of [sensor] in the station's table (what the patch replaces).
 * @param readings how many readings this port has stored locally -- shown so the
 *        installer sees what is riding on the slot before changing it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SensorEditScreen(
    index: Int,
    sensor: SensorInfo,
    existing: List<SensorInfo>,
    pinmap: PinmapMsg?,
    pinmapWarning: String?,
    readings: Long,
    busy: Boolean,
    error: String?,
    onCancel: () -> Unit,
    onSave: (List<SensorPatch>) -> Unit,
    onProbeSdi12Address: (suspend (gpio: Int) -> String?)? = null,
) {
    // Keyed on the sensor: a reload that brings back a different slot must not keep
    // editing the old one's draft.
    val original = remember(sensor) { draftFrom(sensor) }
    var draft by remember(sensor) { mutableStateOf(original) }
    var picking by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    val output = isOutputType(sensor.type)
    val dirty = draft != original
    val patch = draft.toPatchOrNull(sensor.port)

    fun leave() { if (dirty) confirmDiscard = true else onCancel() }

    // The pin picker is the whole board header -- its own screen, one tap away.
    if (picking) {
        SensorPinScreen(
            draft = draft,
            sensor = sensor,
            pinmap = pinmap,
            pinmapWarning = pinmapWarning,
            onSelectTrigger = { draft = draft.copy(gpio = it) },
            onSelectEcho = { draft = draft.copy(gpio2 = it) },
            onBack = { picking = false },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (output) "Editar actuador" else "Editar sensor",
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = { BackIconButton(onClick = { leave() }) },
            )
        },
        bottomBar = {
            EditBar(
                busy = busy,
                // Nothing changed = nothing to send: re-sending the table would cost a
                // BLE round trip and a config write for no reason.
                canSave = dirty && patch != null,
                onCancel = { leave() },
                onSave = { patch?.let { onSave(sensorTableWith(existing, index, it)) } },
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
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            EditHeader(sensor, readings)
            pinmapWarning?.let { WarningCard(it) }

            SettingsGroup(
                header = "Conexión",
                rows = listOf(
                    SettingsRowSpec(
                        icon = TerraIcons.Memory,
                        title = if (needsSecondPin(draft.type)) "Pines (trigger y echo)" else "Pin de conexión",
                        value = pinSummary(draft),
                        onClick = { picking = true },
                    ),
                ),
            )

            if (draft.type.startsWith("sdi12")) {
                EditSection("Dirección SDI-12") {
                    AddrField(draft, onProbeSdi12Address, { draft = it }, supporting = null)
                }
            }

            MappingFields(draft) { draft = it }

            if (!output) {
                EditSection("Cadencia de muestreo") {
                    CadenceFields(draft) { draft = it }
                }
            }

            // The draft is invalid on its own (a blank required field), so say so here
            // rather than leaving Guardar greyed out with no explanation.
            if (dirty && patch == null) {
                Text(
                    "Faltan datos o hay un valor fuera de rango: revisa los campos marcados.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (confirmDiscard) {
        TerraDialog(
            onDismiss = { confirmDiscard = false },
            title = "¿Descartar los cambios?",
            confirmText = "Descartar",
            destructive = true,
            onConfirm = { confirmDiscard = false; onCancel() },
        ) {
            Text("Lo que has cambiado no se ha enviado todavía a la estación.")
        }
    }
}

/** What the slot is and what rides on it, before touching anything. The type is
 *  read-only on purpose: the port keeps its stored readings, and re-typing the slot
 *  would leave two different magnitudes stacked in one history. */
@Composable
private fun EditHeader(sensor: SensorInfo, readings: Long) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(sensorTypeLabel(sensor.type), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(sensorTypeDesc(sensor.type), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Text(
                buildString {
                    append("Puerto ${sensor.port}")
                    if (!isOutputType(sensor.type)) {
                        append(" · ")
                        append(if (readings == 1L) "1 lectura guardada" else "$readings lecturas guardadas")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "El tipo no se cambia aquí: para poner otro sensor en este puerto, elimina " +
                    "éste y crea uno nuevo.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EditSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(title)
        content()
    }
}

/** The per-type decoding fields, grouped under one heading. Types with nothing to
 *  map (DHT11, actuator) contribute no section at all. */
@Composable
private fun MappingFields(draft: SensorDraft, onChange: (SensorDraft) -> Unit) {
    when (draft.type) {
        "sdi12_generic" -> EditSection("Canales") {
            ChannelsEditor(draft, onChange)
            UnitField(draft, onChange)
        }

        "analog_linear" -> EditSection("Valores") {
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

        "onewire_ds18b20" -> EditSection("Valores") {
            KindPicker(draft, onChange)
            DepthField(draft, onChange)
        }

        "hc_sr04" -> EditSection("Valores") {
            UnitField(draft, onChange)
        }
        // "sdi12_aquacheck" (fixed format), "dht11" (fixed outputs) and "actuator"
        // (nothing to decode) have no mapping of their own.
    }
}

/** The pin picker on its own screen: it is a whole board header and would bury the
 *  rest of the fields if it lived inline. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SensorPinScreen(
    draft: SensorDraft,
    sensor: SensorInfo,
    pinmap: PinmapMsg?,
    pinmapWarning: String?,
    onSelectTrigger: (Int) -> Unit,
    onSelectEcho: (Int) -> Unit,
    onBack: () -> Unit,
) {
    // This sensor's own pins read free again, so it can stay where it is.
    val editPins = remember(sensor) { setOfNotNull(sensor.gpio, sensor.gpio2) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Elegir pin", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { BackIconButton(onClick = onBack) },
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            ) {
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Listo") }
            }
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            PinField(
                draft = draft,
                pinmap = pinmap,
                pinmapWarning = pinmapWarning,
                editPins = editPins,
                port = sensor.port,
                editing = true,
                onSelectTrigger = onSelectTrigger,
                onSelectEcho = onSelectEcho,
            )
        }
    }
}

@Composable
private fun EditBar(
    busy: Boolean,
    canSave: Boolean,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = onCancel, enabled = !busy, modifier = Modifier.weight(1f)) {
            Text("Cancelar")
        }
        Button(onClick = onSave, enabled = canSave && !busy, modifier = Modifier.weight(1f)) {
            Text(if (busy) "Guardando…" else "Guardar")
        }
    }
}

private fun pinSummary(draft: SensorDraft): String = buildString {
    append(draft.gpio?.let { "GP$it" } ?: "sin elegir")
    if (needsSecondPin(draft.type)) {
        append(" · echo ")
        append(draft.gpio2?.let { "GP$it" } ?: "sin elegir")
    }
}
