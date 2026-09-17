// Step 2 of the sensor wizard: choose the pin(s) the sensor hangs off. Same board
// and same row layout as the pin map, in PICK mode: the "Compatibles" filter dims
// everything this sensor can't take, and tapping a dimmed pin says why instead of
// doing nothing. Two-pin sensors (HC-SR04) fill one slot at a time on ONE board.
package com.astralink.terralink.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.astralink.terralink.ble.protocol.PinmapMsg

/** Which pin of the sensor the taps are filling. Single-pin sensors only use TRIGGER. */
enum class PinSlot { TRIGGER, ECHO }

/**
 * @param freePins pins the sensor being edited already owns -- they read free again.
 * @param needCaps capability bits this sensor type requires (see [PinCap]).
 * @param twoPins HC-SR04: a trigger and an echo, never the same pin.
 */
@Composable
fun SensorPinPicker(
    pinmap: PinmapMsg?,
    freePins: Set<Int>,
    needCaps: Int,
    twoPins: Boolean,
    gpio: Int?,
    gpio2: Int?,
    onSelect: (slot: PinSlot, gpio: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = pinTones()
    val haptics = LocalHapticFeedback.current
    var slot by remember(twoPins) { mutableStateOf(PinSlot.TRIGGER) }
    var onlyCompatible by remember { mutableStateOf(true) }
    var refused by remember { mutableStateOf<String?>(null) }

    val active = if (slot == PinSlot.ECHO) gpio2 else gpio
    // The other slot's pin shows as taken, so the same pin can't fill both.
    val blocked = if (slot == PinSlot.ECHO) gpio else gpio2
    val cells = remember(pinmap, freePins, blocked) { buildPickerCells(pinmap, freePins, blocked) }
    val chosen = cells.firstOrNull { it.gpio != null && it.gpio == active }
    val eligible = cells.count { it.isEligible(needCaps) }

    Column(modifier.fillMaxWidth()) {
        if (twoPins) {
            SlotChips(slot, gpio, gpio2, t) { slot = it; refused = null }
            Spacer(Modifier.height(12.dp))
        }
        ScopeChips(onlyCompatible, eligible, t) { onlyCompatible = it; refused = null }
        Spacer(Modifier.height(12.dp))
        PickStrip(
            title = chosen?.label,
            tag = chosen?.let { "Pin físico ${it.physical.toString().padStart(2, '0')}" },
            fns = chosen?.fns,
            refused = refused,
            placeholder = "TOCA UN PIN RESALTADO",
            t = t,
        )
        Spacer(Modifier.height(6.dp))
        PinHeaderList(
            cells = cells,
            selected = setOfNotNull(chosen?.physical),
            mode = PinListMode.PICK,
            isVisible = {
                !onlyCompatible || it.isEligible(needCaps) || it.physical == chosen?.physical
            },
            onSelect = { c ->
                if (c.isEligible(needCaps)) {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    refused = null
                    c.gpio?.let { g ->
                        onSelect(slot, g)
                        // Filling the trigger hands the board straight to the echo,
                        // so a two-pin sensor is two taps and no chip hunting.
                        if (twoPins && slot == PinSlot.TRIGGER && gpio2 == null) slot = PinSlot.ECHO
                    }
                } else {
                    haptics.performHapticFeedback(HapticFeedbackType.Reject)
                    refused = c.whyNotSelectable(needCaps)
                }
            },
        )
    }
}

/** Device pinmap -> cells, with the edited sensor's own pins freed and the other
 *  slot's pin blocked. Without an inventory every pin reads free (see the warning
 *  the wizard shows in that case). */
private fun buildPickerCells(pinmap: PinmapMsg?, freePins: Set<Int>, blocked: Int?): List<PinCell> {
    val header = picoWHeader()
    if (pinmap == null) return header
    val live = pinmap.livePins().toMutableMap()
    for (g in freePins) live.remove(g)
    if (blocked != null) {
        live[blocked] = LivePin(PinLive.IN_USE, "sensor", null, live[blocked]?.caps ?: 0)
    }
    return mergePinmap(header, live)
}

@Composable
private fun SlotChips(
    slot: PinSlot,
    gpio: Int?,
    gpio2: Int?,
    t: PinTones,
    onPick: (PinSlot) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        PickChip("Trigger · ${gpio?.let { "GP$it" } ?: "—"}", slot == PinSlot.TRIGGER, t.pick, t) {
            onPick(PinSlot.TRIGGER)
        }
        PickChip("Echo · ${gpio2?.let { "GP$it" } ?: "—"}", slot == PinSlot.ECHO, t.pick, t) {
            onPick(PinSlot.ECHO)
        }
    }
}
