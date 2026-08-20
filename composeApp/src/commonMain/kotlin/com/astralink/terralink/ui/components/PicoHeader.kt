// The Pico W 40-pin header as data: the static silicon map (physical number,
// GP number, silk label, alternate functions, capabilities) merged with the live
// allocation the station reports on ...0015 (free / in use / system-reserved).
// PinHeaderList renders it; the sensor wizard uses the capability bits to decide
// which pins a given sensor may take.
package com.astralink.terralink.ui.components

import com.astralink.terralink.ble.protocol.PinmapMsg

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
    val fns: String = "",         // "UART0 TX · I2C0 SDA", or what a power pin does
)

/** A GPIO is eligible when a sensor is being placed (needCaps != 0), the pin is
 *  free, and it can do every capability the sensor needs. */
fun PinCell.isEligible(needCaps: Int): Boolean =
    gpio != null && live == PinLive.FREE && needCaps != 0 && (caps and needCaps) == needCaps

/** Why this pin refused the tap, in the installer's words. */
fun PinCell.whyNotSelectable(needCaps: Int): String = when {
    gpio == null -> "$label no es un GPIO: $fns."
    live == PinLive.RESERVED -> when (reason) {
        "wireless" -> "GP$gpio lo usa la radio de la placa."
        "wake_btn" -> "GP$gpio es el botón de encendido de la estación."
        "lora_uart" -> "GP$gpio es el puerto serie del módulo LoRa."
        else -> "GP$gpio está reservado por el sistema."
    }
    live == PinLive.IN_USE ->
        port?.let { "GP$gpio ya lo usa el sensor del puerto $it." } ?: "GP$gpio ya está ocupado."
    (caps and needCaps) != needCaps ->
        if (needCaps and PinCap.ADC != 0) "GP$gpio no es analógico: el ADC sólo existe en GP26–GP28."
        else "GP$gpio no puede hacer lo que este sensor necesita."
    else -> "GP$gpio no está disponible."
}

/** Alternate function of each header GPIO, the two the installer actually picks
 *  a pin for (RP2040 datasheet "GPIO Functions"). Shown under the pin label. */
private val ALT_FN = mapOf(
    0 to "UART0 TX · I2C0 SDA", 1 to "UART0 RX · I2C0 SCL",
    2 to "I2C1 SDA · SPI0 SCK", 3 to "I2C1 SCL · SPI0 TX",
    4 to "UART1 TX · I2C0 SDA", 5 to "UART1 RX · I2C0 SCL",
    6 to "I2C1 SDA · SPI0 SCK", 7 to "I2C1 SCL · SPI0 TX",
    8 to "UART1 TX · I2C0 SDA", 9 to "UART1 RX · I2C0 SCL",
    10 to "I2C1 SDA · SPI1 SCK", 11 to "I2C1 SCL · SPI1 TX",
    12 to "UART0 TX · SPI1 RX", 13 to "UART0 RX · SPI1 CSn",
    14 to "I2C1 SDA · SPI1 SCK", 15 to "I2C1 SCL · SPI1 TX",
    16 to "UART0 TX · I2C0 SDA", 17 to "UART0 RX · I2C0 SCL",
    18 to "I2C1 SDA · SPI0 SCK", 19 to "I2C1 SCL · SPI0 TX",
    20 to "I2C0 SDA · SPI0 RX", 21 to "I2C0 SCL · SPI0 CSn",
    22 to "I2C1 SDA · SPI0 SCK",
    26 to "ADC0 · I2C1 SDA", 27 to "ADC1 · I2C1 SCL", 28 to "ADC2 · SPI1 RX",
)

/** What each non-GPIO header pin is, by silk-screen label. */
private val PIN_DESC = mapOf(
    "GND" to "Tierra",
    "AGND" to "Tierra analógica",
    "VBUS" to "5 V del USB",
    "VSYS" to "Entrada 1.8 – 5.5 V",
    "3V3_EN" to "Habilita el regulador",
    "3V3" to "Salida 3.3 V",
    "VREF" to "Referencia del ADC",
    "RUN" to "Reinicio / enable",
)

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
        Row(34, 28, "GP28", "ADC2", PinRole.GPIO), Row(33, null, "AGND", null, PinRole.GND),
        Row(32, 27, "GP27", "ADC1", PinRole.GPIO), Row(31, 26, "GP26", "ADC0", PinRole.GPIO),
        Row(30, null, "RUN", null, PinRole.SYS), Row(29, 22, "GP22", null, PinRole.GPIO),
        Row(28, null, "GND", null, PinRole.GND), Row(27, 21, "GP21", null, PinRole.GPIO),
        Row(26, 20, "GP20", null, PinRole.GPIO), Row(25, 19, "GP19", null, PinRole.GPIO),
        Row(24, 18, "GP18", null, PinRole.GPIO), Row(23, null, "GND", null, PinRole.GND),
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
            fns = r.gpio?.let { ALT_FN[it] } ?: PIN_DESC[r.label] ?: "",
        )
    }
    return build(left, PinSide.LEFT) + build(right, PinSide.RIGHT)
}

/** One pin as the station reports it in ...0015. */
data class LivePin(
    val state: PinLive,
    val reason: String?,
    val port: Int?,
    val caps: Int = 0,      // 0 = the station didn't report caps (older firmware)
)

/** Device pinmap -> gpio -> live state, capabilities included. */
fun PinmapMsg.livePins(): Map<Int, LivePin> = pins.associate { p ->
    val state = when (p.state) {
        "in_use" -> PinLive.IN_USE
        "reserved" -> PinLive.RESERVED
        else -> PinLive.FREE
    }
    p.gpio to LivePin(state, p.reason.ifBlank { null }, p.port, p.caps)
}

/**
 * Merge the static header with the device's live pinmap. Call after reading ...0015.
 *
 * Capabilities come from the STATION when it reports them: which pins can do ADC
 * (and therefore host an analog sensor) is the firmware's rule, and a static copy
 * here would silently drift from it. The header's own caps are only the fallback.
 */
fun mergePinmap(
    header: List<PinCell>,
    live: Map<Int, LivePin>,
): List<PinCell> = header.map { c ->
    val l = c.gpio?.let { live[it] } ?: return@map c
    c.copy(
        live = l.state, reason = l.reason, port = l.port,
        caps = if (l.caps != 0) l.caps else c.caps,
    )
}
