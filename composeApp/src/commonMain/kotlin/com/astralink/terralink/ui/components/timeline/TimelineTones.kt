// The palette and type the lifecycle screens share. Derived from the theme rather
// than carrying their own hexes, the same way the pin map's tones are, so these
// screens stay in the app's colours instead of forking a second design system.
package com.astralink.terralink.ui.components.timeline

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

internal data class TimeTones(
    val accent: Color,
    val ink: Color,
    val muted: Color,
    val faint: Color,
    val ghost: Color,
    val hairline: Color,
    val rail: Color,        // the sleep bar: where the station is doing nothing
    val lane: Color,        // the baseline under each lane
    val sheet: Color,       // the detail bar, lifted off the page
    val alert: Color,       // a failure, or a value past a line that matters
    val future: Color,      // the outline of something that hasn't happened yet
)

@Composable
internal fun timeTones(): TimeTones {
    val cs = MaterialTheme.colorScheme
    return TimeTones(
        accent = cs.primary,
        ink = cs.onSurface,
        muted = cs.onSurfaceVariant,
        faint = cs.onSurfaceVariant.copy(alpha = 0.62f),
        ghost = cs.onSurfaceVariant.copy(alpha = 0.42f),
        hairline = cs.outlineVariant.copy(alpha = 0.55f),
        rail = cs.surfaceContainerHigh,
        lane = cs.outlineVariant.copy(alpha = 0.35f),
        sheet = cs.surfaceContainerLowest,
        alert = cs.error,
        future = cs.outline.copy(alpha = 0.55f),
    )
}

internal val Mono = FontFamily.Monospace

/** The small uppercase mono label the design uses for every field name. */
@Composable
internal fun eyebrow(color: Color, size: Float = 8.5f) = TextStyle(
    fontFamily = Mono, fontSize = size.sp, letterSpacing = 0.14.em, color = color,
)

@Composable
internal fun monoValue(color: Color, size: Float = 15f) = TextStyle(
    fontFamily = Mono, fontSize = size.sp, fontWeight = FontWeight.Medium,
    letterSpacing = (-0.01).em, color = color,
)
