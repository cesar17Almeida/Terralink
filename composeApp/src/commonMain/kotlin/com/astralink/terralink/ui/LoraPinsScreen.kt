// Placing or moving the LoRa module from Connectivity: one screen, the board
// itself, and Guardar only once the pair changed. The first-run wizard shows the
// same field inline; this is the edit path, so it also asks before discarding.
package com.astralink.terralink.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.astralink.terralink.ble.protocol.ConfigPatchMsg
import com.astralink.terralink.ble.protocol.ConfigSnapshotMsg
import com.astralink.terralink.ble.protocol.PinmapMsg
import com.astralink.terralink.ble.session.ActiveSession
import com.astralink.terralink.ui.components.BackIconButton
import com.astralink.terralink.ui.components.LoraPinPicker
import com.astralink.terralink.ui.components.TerraDialog
import com.astralink.terralink.ui.components.loraFreePairs
import com.astralink.terralink.ui.components.loraPickerCells
import kotlinx.coroutines.launch

private sealed class LoraPinsPhase {
    data object Loading : LoraPinsPhase()
    data class Ready(val config: ConfigSnapshotMsg, val pinmap: PinmapMsg?) : LoraPinsPhase()
    data class Failed(val message: String) : LoraPinsPhase()
}

/** The pair the module sits on today, or null when the config has it switched off. */
internal fun configuredLoraPair(config: ConfigSnapshotMsg): Pair<Int, Int>? =
    if (config.lora) config.loraTx?.let { tx -> config.loraRx?.let { rx -> tx to rx } } else null

/** Shown when the station's pin inventory could not be read: every pin then draws free. */
private const val LORA_PINMAP_WARNING =
    "No se pudo leer el mapa de pines de la estación. Se muestran todos los pines como libres: " +
        "si eliges un par ocupado o reservado, la estación rechazará el módulo al guardar."

/** The board with the module's pair on it, plus what to wire where. Shared by the
 *  wizard step and [LoraPinsScreen]; emits its parts into the caller's column. */
@Composable
internal fun LoraPinsField(pinmap: PinmapMsg?, selected: Pair<Int, Int>?, onSelect: (Pair<Int, Int>) -> Unit) {
    if (pinmap == null) WarningCard(LORA_PINMAP_WARNING)
    LoraPinPicker(pinmap = pinmap, selected = selected, onSelect = onSelect)
    val note = when {
        selected != null ->
            "Cableado: TX del módulo → GP${selected.second}, RX del módulo → GP${selected.first}, más 3V3 y GND."
        pinmap != null && loraFreePairs(loraPickerCells(pinmap)).isEmpty() ->
            "No queda ningún puerto serie libre: libera pines en Periféricos."
        else -> null
    }
    note?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Add or move the module. Saving writes the station and hands back to the caller,
 *  which re-reads and shows the new pins on the module's row. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoraPinsScreen(active: ActiveSession, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var phase by remember { mutableStateOf<LoraPinsPhase>(LoraPinsPhase.Loading) }
    var reloadKey by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }

    LaunchedEffect(reloadKey) {
        phase = LoraPinsPhase.Loading
        phase = try {
            LoraPinsPhase.Ready(active.readConfig(), runCatching { active.readPinmap() }.getOrNull())
        } catch (e: Throwable) {
            LoraPinsPhase.Failed(e.message ?: "No se pudo leer la estación")
        }
    }

    val cfg = (phase as? LoraPinsPhase.Ready)?.config
    val current = cfg?.let(::configuredLoraPair)
    var pair by remember(current) { mutableStateOf(current) }
    // Nothing changed = nothing to send: the same pair would cost a config write for no reason.
    val dirty = pair != null && pair != current

    fun leave() { if (dirty) confirmDiscard = true else onBack() }
    fun save() {
        val p = pair ?: return
        busy = true; error = null
        scope.launch {
            try {
                active.writeConfig(ConfigPatchMsg(lora = true, loraTx = p.first, loraRx = p.second))
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onBack()
            } catch (e: Throwable) {
                error = e.message ?: "No se pudo guardar"
            } finally {
                busy = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (cfg?.lora == true) "Pines del módulo LoRa" else "Añadir módulo LoRa",
                        fontWeight = FontWeight.SemiBold)
                },
                navigationIcon = { BackIconButton(onClick = ::leave) },
            )
        },
        bottomBar = { EditBar(busy = busy, canSave = dirty, onCancel = ::leave, onSave = ::save) },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (val p = phase) {
                LoraPinsPhase.Loading -> Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) {
                    CircularProgressIndicator()
                }
                is LoraPinsPhase.Failed -> {
                    Text(p.message, color = MaterialTheme.colorScheme.error)
                    Button(onClick = { reloadKey++ }) { Text("Reintentar") }
                }
                is LoraPinsPhase.Ready -> {
                    Text(
                        "El Wio-E5 habla por un puerto serie: toca en el mapa el par de pines (TX · RX) " +
                            "de la estación donde está conectado.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    LoraPinsField(pinmap = p.pinmap, selected = pair, onSelect = { pair = it })
                    error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    if (confirmDiscard) {
        TerraDialog(
            onDismiss = { confirmDiscard = false },
            title = "¿Descartar los cambios?",
            confirmText = "Descartar",
            destructive = true,
            onConfirm = { confirmDiscard = false; onBack() },
        ) {
            Text("El par elegido no se ha enviado todavía a la estación.")
        }
    }
}
