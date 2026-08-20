// Sticky footer of the pin map. It replaces a static legend: with nothing
// selected it explains the three dot colours, and once a pin is tapped it turns
// into that pin's card -- state, what occupies it, and its alternate functions.
package com.astralink.terralink.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * @param cell the pin the user tapped, or null for the legend.
 * @param detail one line saying what holds the pin (sensor, peripheral, rail).
 */
@Composable
fun PinDetailBar(
    cell: PinCell?,
    detail: String,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = pinTones()
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shadowElevation = 10.dp,
    ) {
        Column {
            HorizontalDivider(color = t.hairline)
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 17.dp)
                    .defaultMinSize(minHeight = 62.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (cell == null) PinLegend(t) else PinDetail(cell, detail, onClear, t)
            }
        }
    }
}

@Composable
private fun PinDetail(cell: PinCell, detail: String, onClear: () -> Unit, t: PinTones) {
    val state = cell.pinState()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(1f)) {
            Row {
                Text(
                    cell.label,
                    modifier = Modifier.alignByBaseline(),
                    style = TextStyle(
                        fontFamily = Mono, fontSize = 21.sp, fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.02).em, color = t.ink,
                    ),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "Pin físico ${cell.physical.toString().padStart(2, '0')}",
                    modifier = Modifier.alignByBaseline(),
                    style = TextStyle(
                        fontFamily = Mono, fontSize = 8.5.sp, letterSpacing = 0.14.em,
                        color = t.muted.copy(alpha = 0.7f),
                    ),
                )
            }
            Spacer(Modifier.height(7.dp))
            Text(
                detail,
                style = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp, color = t.muted),
            )
            if (cell.role == PinRole.GPIO && cell.fns.isNotEmpty()) {
                Spacer(Modifier.height(9.dp))
                Text(
                    cell.fns.uppercase(),
                    style = TextStyle(
                        fontFamily = Mono, fontSize = 8.5.sp,
                        letterSpacing = 0.08.em, color = t.faint,
                    ),
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            StateChip(state, t)
            Spacer(Modifier.height(12.dp))
            Text(
                "CERRAR",
                modifier = Modifier.clickable(onClick = onClear).padding(4.dp),
                style = TextStyle(
                    fontFamily = Mono, fontSize = 8.5.sp,
                    letterSpacing = 0.12.em, color = t.muted.copy(alpha = 0.7f),
                ),
            )
        }
    }
}

@Composable
private fun StateChip(state: PinState, t: PinTones) {
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = when (state) {
        PinState.USED -> t.accent.copy(alpha = 0.12f) to t.accent
        PinState.FREE -> cs.surfaceContainer to t.muted
        PinState.SYSTEM -> cs.surfaceContainerHigh to t.muted
    }
    Box(
        Modifier
            .height(23.dp)
            .background(bg, RoundedCornerShape(percent = 50))
            .padding(horizontal = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            pinStateLabel(state).uppercase(),
            style = TextStyle(
                fontFamily = Mono, fontSize = 8.5.sp, letterSpacing = 0.1.em, color = fg,
            ),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PinLegend(t: PinTones) {
    Column {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LegendItem(t.freeDot, t.freeEdge, "Libre", t)
            LegendItem(t.accent, t.accent, "En uso", t)
            LegendItem(t.sysDot, t.sysDot, "Sistema", t)
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "TOCA UN PIN PARA VER SU DETALLE",
            style = TextStyle(
                fontFamily = Mono, fontSize = 8.5.sp, letterSpacing = 0.12.em, color = t.faint,
            ),
        )
    }
}

@Composable
private fun LegendItem(fill: Color, edge: Color, label: String, t: PinTones) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(10.dp)
                .background(fill, CircleShape)
                .border(1.5.dp, edge, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, style = TextStyle(fontSize = 11.5.sp, color = t.muted))
    }
}

internal fun pinStateLabel(state: PinState): String = when (state) {
    PinState.FREE -> "Libre"
    PinState.USED -> "En uso"
    PinState.SYSTEM -> "Sistema"
}
