// Shared tones and text helpers for the live soil-probe screen.
package com.astralink.terralink.ui.components.soilprobe

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

/** Temperature is a second series next to moisture, so it gets a hue no theme role
 *  owns (the theme's greens would make it read as more soil). Bar and its label. */
val TemperatureBarColor = Color(0xFFE9953A)
val TemperatureTextColor = Color(0xFFB45309)

/** Moisture bars fill at this value: the probe's water calibration point (SFU). */
const val MOISTURE_SCALE_MAX = 100f

/** Temperature bars span this range (degrees C). */
const val TEMPERATURE_SCALE_MIN = 10f
const val TEMPERATURE_SCALE_MAX = 35f

/** The one micro-label: 11 sp, semibold, tracked; callers pass the text uppercased. */
@Composable
fun microLabelStyle(): TextStyle = MaterialTheme.typography.labelSmall.copy(
    fontSize = 11.sp,
    fontWeight = FontWeight.SemiBold,
    letterSpacing = 1.sp,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
)

/** Digits that line up between rows. */
const val TABULAR_NUMS = "tnum"

/** One decimal, no locale: "22.4", "-2.3". */
fun formatOneDecimal(v: Float): String {
    val scaled = (v * 10f).roundToInt()
    val a = abs(scaled)
    return (if (scaled < 0) "-" else "") + "${a / 10}.${a % 10}"
}
