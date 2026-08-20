// Step 2 of the sensor wizard: choose the pin(s) the sensor hangs off. Same board
// and same row layout as the pin map, in PICK mode: the "Compatibles" filter dims
// everything this sensor can't take, and tapping a dimmed pin says why instead of
// doing nothing. Two-pin sensors (HC-SR04) fill one slot at a time on ONE board.
package com.astralink.terralink.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
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
        ChoiceStrip(chosen, refused, t)
        Spacer(Modifier.height(6.dp))
        PinHeaderList(
            cells = cells,
            selected = chosen?.physical,
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
        Chip("Trigger · ${gpio?.let { "GP$it" } ?: "—"}", slot == PinSlot.TRIGGER, t.pick, t) {
            onPick(PinSlot.TRIGGER)
        }
        Chip("Echo · ${gpio2?.let { "GP$it" } ?: "—"}", slot == PinSlot.ECHO, t.pick, t) {
            onPick(PinSlot.ECHO)
        }
    }
}

@Composable
private fun ScopeChips(onlyCompatible: Boolean, eligible: Int, t: PinTones, onPick: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Chip("Compatibles · $eligible", onlyCompatible, t.accent, t) { onPick(true) }
        Chip("Todos", !onlyCompatible, t.accent, t) { onPick(false) }
    }
}

@Composable
private fun Chip(label: String, on: Boolean, accent: Color, t: PinTones, onClick: () -> Unit) {
    val shape = RoundedCornerShape(percent = 50)
    Box(
        Modifier
            .height(31.dp)
            .background(if (on) accent else Color.Transparent, shape)
            .border(1.dp, if (on) accent else t.hairline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label.uppercase(),
            style = TextStyle(
                fontFamily = Mono, fontSize = 8.5.sp, letterSpacing = 0.12.em,
                color = if (on) MaterialTheme.colorScheme.onPrimary else t.muted,
            ),
        )
    }
}

/** Same card as the pin map's detail bar, un-elevated: this one lives inside the
 *  wizard's scroll, above the board, because the wizard already owns the footer. */
@Composable
private fun ChoiceStrip(chosen: PinCell?, refused: String?, t: PinTones) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest, shape)
            .border(1.dp, if (refused != null) MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
                else t.hairline, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        when {
            refused != null -> Text(
                refused,
                style = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.error),
            )
            chosen != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row {
                        Text(
                            chosen.label,
                            modifier = Modifier.alignByBaseline(),
                            style = TextStyle(
                                fontFamily = Mono, fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = (-0.02).em, color = t.pick,
                            ),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Pin físico ${chosen.physical.toString().padStart(2, '0')}",
                            modifier = Modifier.alignByBaseline(),
                            style = TextStyle(
                                fontFamily = Mono, fontSize = 8.5.sp,
                                letterSpacing = 0.14.em, color = t.muted.copy(alpha = 0.7f),
                            ),
                        )
                    }
                    if (chosen.fns.isNotEmpty()) {
                        Spacer(Modifier.height(7.dp))
                        Text(
                            chosen.fns.uppercase(),
                            style = TextStyle(
                                fontFamily = Mono, fontSize = 8.5.sp,
                                letterSpacing = 0.08.em, color = t.faint,
                            ),
                        )
                    }
                }
                Box(
                    Modifier
                        .height(23.dp)
                        .background(t.pick.copy(alpha = 0.12f), RoundedCornerShape(percent = 50))
                        .padding(horizontal = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "ELEGIDO",
                        style = TextStyle(
                            fontFamily = Mono, fontSize = 8.5.sp,
                            letterSpacing = 0.1.em, color = t.pick,
                        ),
                    )
                }
            }
            else -> Text(
                "TOCA UN PIN RESALTADO",
                style = TextStyle(
                    fontFamily = Mono, fontSize = 8.5.sp, letterSpacing = 0.12.em, color = t.faint,
                ),
            )
        }
    }
}
