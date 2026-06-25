// Interactive Pico W pinout. Renders the 40-pin header as a vector board and
// colours each GPIO from the station's live pinmap (BLE characteristic ...0015):
// free / in-use (shows its port) / system-reserved. When a sensor type is being
// placed, the pins it could legally take (free AND capable) pulse as eligible;
// tapping one calls onSelect(gpio). Non-GPIO pins (GND / power / RUN) are inert.
package com.astralink.terralink.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp

// --- Pin capability bits: mirror SAVIA_PIN_CAP_* in savia_c/include/savia/pinmap.h ---
object PinCap {
    const val DIGITAL = 1 shl 0
    const val PIO     = 1 shl 1   // SDI-12, 1-Wire, any bit-banged proto
    const val PWM     = 1 shl 2
    const val ADC     = 1 shl 3   // analog input -- GP26..GP28 only
    const val I2C     = 1 shl 4
    const val SPI     = 1 shl 5
    const val UART    = 1 shl 6
}

enum class PinRole { GPIO, GND, PWR, SYS }
enum class PinSide { LEFT, RIGHT }
enum class PinLive { FREE, IN_USE, RESERVED }   // GPIO state from ...0015

/** One header position. Static fields come from the Pico W silicon map;
 *  caps/live/reason/port are merged from the device's pinmap read (...0015). */
data class PinCell(
    val physical: Int,            // 1..40, as silk-screened
    val side: PinSide,
    val order: Int,               // 0..19, top -> bottom within its column
    val gpio: Int?,               // GP number, or null for power/GND/RUN
    val label: String,            // "GP0", "GND", "3V3", "VBUS", ...
    val adc: String? = null,      // "ADC0".."ADC2" for GP26..28
    val role: PinRole,
    val caps: Int = 0,            // OR of PinCap.* (0 for non-GPIO)
    val live: PinLive = PinLive.FREE,
    val reason: String? = null,   // "wireless" | "wake_btn" | "lora_uart" | "sensor"
    val port: Int? = null,        // 1..6 when live == IN_USE
)

/** A GPIO is eligible when a sensor is being placed (needCaps != 0), the pin is
 *  free, and it can do every capability the sensor needs. */
fun PinCell.isEligible(needCaps: Int): Boolean =
    gpio != null && live == PinLive.FREE && needCaps != 0 && (caps and needCaps) == needCaps

/**
 * Static Pico W header (USB at top). GP23/24/25/29 are internal to the CYW43 and
 * not on the header, so they never appear here -- which is exactly why the wireless
 * reservation is invisible to the user. Live state is merged in from ...0015.
 */
fun picoWHeader(): List<PinCell> {
    val gpioCaps = PinCap.DIGITAL or PinCap.PIO or PinCap.PWM or
        PinCap.I2C or PinCap.SPI or PinCap.UART
    val adcCaps = gpioCaps or PinCap.ADC

    // (physical, gpio, label, adcTag, role)
    data class Row(val phys: Int, val gpio: Int?, val label: String,
                   val adc: String?, val role: PinRole)

    val left = listOf(
        Row(1, 0, "GP0", null, PinRole.GPIO), Row(2, 1, "GP1", null, PinRole.GPIO),
        Row(3, null, "GND", null, PinRole.GND), Row(4, 2, "GP2", null, PinRole.GPIO),
        Row(5, 3, "GP3", null, PinRole.GPIO), Row(6, 4, "GP4", null, PinRole.GPIO),
        Row(7, 5, "GP5", null, PinRole.GPIO), Row(8, null, "GND", null, PinRole.GND),
        Row(9, 6, "GP6", null, PinRole.GPIO), Row(10, 7, "GP7", null, PinRole.GPIO),
        Row(11, 8, "GP8", null, PinRole.GPIO), Row(12, 9, "GP9", null, PinRole.GPIO),
        Row(13, null, "GND", null, PinRole.GND), Row(14, 10, "GP10", null, PinRole.GPIO),
        Row(15, 11, "GP11", null, PinRole.GPIO), Row(16, 12, "GP12", null, PinRole.GPIO),
        Row(17, 13, "GP13", null, PinRole.GPIO), Row(18, null, "GND", null, PinRole.GND),
        Row(19, 14, "GP14", null, PinRole.GPIO), Row(20, 15, "GP15", null, PinRole.GPIO),
    )
    // Right column drawn top -> bottom as physical 40..21 (board orientation).
    val right = listOf(
        Row(40, null, "VBUS", null, PinRole.PWR), Row(39, null, "VSYS", null, PinRole.PWR),
        Row(38, null, "GND", null, PinRole.GND), Row(37, null, "3V3_EN", null, PinRole.PWR),
        Row(36, null, "3V3", null, PinRole.PWR), Row(35, null, "VREF", null, PinRole.PWR),
        Row(34, 28, "GP28", "ADC2", PinRole.GPIO), Row(33, null, "GND", null, PinRole.GND),
        Row(32, 27, "GP27", "ADC1", PinRole.GPIO), Row(31, 26, "GP26", "ADC0", PinRole.GPIO),
        Row(30, null, "RUN", null, PinRole.SYS), Row(29, 22, "GP22", null, PinRole.GPIO),
        Row(28, null, "GND", null, PinRole.GND), Row(27, 21, "GP21", null, PinRole.GPIO),
        Row(26, 20, "GP20", null, PinRole.GPIO), Row(25, null, "GND", null, PinRole.GND),
        Row(24, 19, "GP19", null, PinRole.GPIO), Row(23, 18, "GP18", null, PinRole.GPIO),
        Row(22, 17, "GP17", null, PinRole.GPIO), Row(21, 16, "GP16", null, PinRole.GPIO),
    )

    fun build(rows: List<Row>, side: PinSide) = rows.mapIndexed { i, r ->
        PinCell(
            physical = r.phys, side = side, order = i, gpio = r.gpio, label = r.label,
            adc = r.adc, role = r.role,
            caps = when {
                r.role != PinRole.GPIO -> 0
                r.adc != null -> adcCaps
                else -> gpioCaps
            },
        )
    }
    return build(left, PinSide.LEFT) + build(right, PinSide.RIGHT)
}

/**
 * Merge the static header with the device's live pinmap (gpio -> live/reason/port,
 * and caps if you trust the device over the static map). Call after reading ...0015.
 */
fun mergePinmap(
    header: List<PinCell>,
    live: Map<Int, Triple<PinLive, String?, Int?>>,   // gpio -> (state, reason, port)
): List<PinCell> = header.map { c ->
    val l = c.gpio?.let { live[it] } ?: return@map c
    c.copy(live = l.first, reason = l.second, port = l.third)
}

private const val ROW_COUNT = 20

@Composable
fun PicoPinout(
    cells: List<PinCell>,
    needCaps: Int,                 // caps the sensor being placed requires (0 = browse only)
    onSelect: (gpio: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()

    val pulse by rememberInfiniteTransition(label = "pin-pulse").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "pulse",
    )

    Canvas(
        modifier = modifier
            .aspectRatio(0.62f)
            .pointerInput(cells, needCaps) {
                detectTapGestures { tap ->
                    val g = geom(size)
                    cells.firstOrNull { c ->
                        c.isEligible(needCaps) && (tap - g.center(c)).getDistanceSquared() <= g.padR * g.padR * 1.6f
                    }?.gpio?.let(onSelect)
                }
            },
    ) {
        val g = geom(IntSize(size.width.toInt(), size.height.toInt()))

        // Board body + USB tab.
        drawRoundCard(g, cs.surfaceVariant.copy(alpha = 0.45f), cs.outline.copy(alpha = 0.4f))

        cells.forEach { c ->
            val center = g.center(c)
            val eligible = c.isEligible(needCaps)
            val fill: Color
            val ring: Color?
            when {
                c.role != PinRole.GPIO -> { fill = cs.outline.copy(alpha = 0.35f); ring = null }
                c.live == PinLive.IN_USE -> { fill = cs.primary; ring = null }
                c.live == PinLive.RESERVED -> { fill = cs.surfaceVariant; ring = cs.outline.copy(alpha = 0.5f) }
                eligible -> { fill = cs.tertiaryContainer; ring = cs.tertiary }
                needCaps != 0 -> { fill = cs.surface; ring = cs.outline.copy(alpha = 0.25f) } // free but not capable
                else -> { fill = cs.surface; ring = cs.outline.copy(alpha = 0.5f) }
            }

            // Eligible halo (the "animation").
            if (eligible) {
                drawCircle(
                    color = cs.tertiary.copy(alpha = 0.18f + 0.22f * pulse),
                    radius = g.padR * (1.5f + 0.5f * pulse), center = center,
                )
            }
            drawCircle(fill, g.padR, center)
            ring?.let { drawCircle(it, g.padR, center, style = Stroke(width = g.padR * 0.16f)) }

            // In-use pins show their port number; ADC pins a tiny tag.
            val badge = when {
                c.live == PinLive.IN_USE && c.port != null -> c.port.toString()
                else -> null
            }
            if (badge != null) {
                val tl = measurer.measure(badge, TextStyle(fontSize = g.badgeSp, color = cs.onPrimary))
                drawText(tl, topLeft = center - Offset(tl.size.width / 2f, tl.size.height / 2f))
            }

            // Outboard label (GP#, with ADC tag under it).
            val labelColor = if (c.role == PinRole.GPIO) cs.onSurface else cs.onSurface.copy(alpha = 0.45f)
            val text = c.adc?.let { "${c.label} · $it" } ?: c.label
            val lt = measurer.measure(text, TextStyle(fontSize = g.labelSp, color = labelColor))
            val lx = if (c.side == PinSide.LEFT) g.leftPadX - g.padR * 1.8f - lt.size.width
            else g.rightPadX + g.padR * 1.8f
            drawText(lt, topLeft = Offset(lx, center.y - lt.size.height / 2f))
        }
    }
}

// --- geometry, computed from the canvas size ---
private class Geom(val size: Size) {
    val top = size.height * 0.055f
    val rowH = (size.height * 0.945f - top) / (ROW_COUNT - 1)
    val boardL = size.width * 0.34f
    val boardR = size.width * 0.66f
    val leftPadX = boardL + (boardR - boardL) * 0.18f
    val rightPadX = boardR - (boardR - boardL) * 0.18f
    val padR = (rowH * 0.32f).coerceAtMost(size.width * 0.03f)
    val labelSp = (size.height * 0.0155f).coerceIn(8f, 13f).sp
    val badgeSp = (padR * 0.13f).coerceIn(7f, 12f).sp
    fun center(c: PinCell) = Offset(
        if (c.side == PinSide.LEFT) leftPadX else rightPadX,
        top + c.order * rowH,
    )
}
// Plain function (not a DrawScope extension): it's also called from the tap handler,
// whose PointerInputScope is not a DrawScope. It uses no DrawScope members anyway.
private fun geom(size: IntSize) = Geom(Size(size.width.toFloat(), size.height.toFloat()))

private fun DrawScope.drawRoundCard(g: Geom, body: Color, edge: Color) {
    val tl = Offset(g.boardL, g.top - g.rowH * 0.6f)
    val s = Size(g.boardR - g.boardL, (ROW_COUNT - 1) * g.rowH + g.rowH * 1.2f)
    drawRoundRect(body, tl, s, cornerRadius = androidx.compose.ui.geometry.CornerRadius(s.width * 0.10f))
    drawRoundRect(edge, tl, s, cornerRadius = androidx.compose.ui.geometry.CornerRadius(s.width * 0.10f),
        style = Stroke(width = g.padR * 0.18f))
}
