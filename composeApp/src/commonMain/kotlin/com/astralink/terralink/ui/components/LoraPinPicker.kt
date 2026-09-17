// Where the LoRa module goes: the same 40-pin list the sensor wizard uses, in PICK
// mode, where a tap on either pin of a UART pair takes the whole pair -- TX and RX
// are never chosen apart. The "Compatibles" filter dims everything that is not a
// free serial port, and tapping a dimmed pin says why instead of doing nothing.
package com.astralink.terralink.ui.components

import androidx.compose.foundation.layout.Column
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

/** UART (TX, RX) pairs the Wio-E5 can hang on: adjacent pins of one block, as the
 *  Grove cable lands them. GP28 is a UART0 TX too, but its RX (GP29) is the radio's. */
val LORA_UART_PAIRS: List<Pair<Int, Int>> = listOf(0 to 1, 4 to 5, 8 to 9, 12 to 13, 16 to 17, 20 to 21)

/** Which UART block a pair belongs to (RP2040/RP2350 GPIO function table). */
fun loraUartName(tx: Int): String = if (tx == 0 || tx == 12 || tx == 16) "UART0" else "UART1"

/** The pair a header GPIO belongs to, or null when it is not a serial pin. */
fun loraPairOf(gpio: Int): Pair<Int, Int>? =
    LORA_UART_PAIRS.firstOrNull { gpio == it.first || gpio == it.second }

/** Header cells with the module's own pins reading free: they are where it is
 *  today, not an obstacle to moving it. Without an inventory every pin reads free. */
fun loraPickerCells(pinmap: PinmapMsg?): List<PinCell> {
    val header = picoWHeader()
    if (pinmap == null) return header
    return mergePinmap(header, pinmap.livePins().filterValues { it.reason != "lora_uart" })
}

/** The pairs whose two pins both read free in [cells]. */
fun loraFreePairs(cells: List<PinCell>): List<Pair<Int, Int>> {
    val free = cells.filter { it.gpio != null && it.live == PinLive.FREE }.mapNotNull { it.gpio }.toSet()
    return LORA_UART_PAIRS.filter { it.first in free && it.second in free }
}

/** @param selected the pair the module is on, or about to be: both pins draw as chosen. */
@Composable
fun LoraPinPicker(
    pinmap: PinmapMsg?,
    selected: Pair<Int, Int>?,
    onSelect: (Pair<Int, Int>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = pinTones()
    val haptics = LocalHapticFeedback.current
    var onlyCompatible by remember { mutableStateOf(true) }
    var refused by remember { mutableStateOf<String?>(null) }

    val cells = remember(pinmap) { loraPickerCells(pinmap) }
    val freePairs = remember(cells) { loraFreePairs(cells) }
    val chosenGpios = selected?.let { setOf(it.first, it.second) } ?: emptySet()
    val chosenPhysical = cells.filter { it.gpio != null && it.gpio in chosenGpios }.map { it.physical }.toSet()
    fun eligible(c: PinCell) = c.gpio?.let(::loraPairOf)?.let { it in freePairs } == true

    Column(modifier.fillMaxWidth()) {
        ScopeChips(onlyCompatible, freePairs.size, t) { onlyCompatible = it; refused = null }
        Spacer(Modifier.height(12.dp))
        PickStrip(
            title = selected?.let { "GP${it.first} · GP${it.second}" },
            tag = selected?.let { p ->
                val physical = listOf(p.first, p.second).map { g -> cells.first { it.gpio == g }.physical }
                "Pines físicos " + physical.joinToString(" · ") { it.toString().padStart(2, '0') }
            },
            fns = selected?.let { "${loraUartName(it.first)} · TX GP${it.first} · RX GP${it.second}" },
            refused = refused,
            placeholder = if (pinmap != null && freePairs.isEmpty()) "NO QUEDA NINGÚN PUERTO SERIE LIBRE"
                else "TOCA UN PIN DE UN PUERTO SERIE",
            t = t,
        )
        Spacer(Modifier.height(6.dp))
        PinHeaderList(
            cells = cells,
            selected = chosenPhysical,
            mode = PinListMode.PICK,
            isVisible = { !onlyCompatible || eligible(it) || (it.gpio != null && it.gpio in chosenGpios) },
            onSelect = { c ->
                val pair = c.gpio?.let(::loraPairOf)
                if (pair != null && pair in freePairs) {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    refused = null
                    onSelect(pair)
                } else {
                    haptics.performHapticFeedback(HapticFeedbackType.Reject)
                    refused = whyNotLora(c, cells)
                }
            },
        )
    }
}

/** Why this pin refused the tap, in the installer's words. */
private fun whyNotLora(cell: PinCell, cells: List<PinCell>): String {
    val g = cell.gpio ?: return "${cell.label} no es un GPIO: ${cell.fns}."
    val pair = loraPairOf(g)
        ?: return "GP$g no es un puerto serie: el módulo va en un par TX·RX contiguo " +
            "(GP0·1, GP4·5, GP8·9, GP12·13, GP16·17 o GP20·21)."
    if (cell.live != PinLive.FREE) return cell.whyNotSelectable(PinCap.UART)
    val other = if (g == pair.first) pair.second else pair.first
    val partner = cells.firstOrNull { it.gpio == other } ?: return "GP$g no está disponible."
    return "El módulo ocupa el par GP${pair.first}·GP${pair.second}. " + partner.whyNotSelectable(PinCap.UART)
}
