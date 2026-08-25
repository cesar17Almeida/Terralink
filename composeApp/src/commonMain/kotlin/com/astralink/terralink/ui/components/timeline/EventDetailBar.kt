// The bar under the track: the legend when nothing is picked, the picked event's
// story when something is. Same slot either way, so selecting doesn't move the
// track under the user's thumb.
package com.astralink.terralink.ui.components.timeline

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.astralink.terralink.timeline.EventKind
import com.astralink.terralink.timeline.StationEvent

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EventDetailBar(
    event: StationEvent?,
    nowMs: Long,
    utcOffsetMin: Int,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = timeTones()
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = t.sheet,
        shadowElevation = 6.dp,
    ) {
        Column {
            HorizontalDivider(color = t.hairline)
            Box(Modifier.padding(horizontal = 22.dp, vertical = 16.dp).heightIn(min = 86.dp)) {
                if (event == null) Legend() else EventCard(event, nowMs, utcOffsetMin, onClear)
            }
        }
    }
}

@Composable
private fun EventCard(event: StationEvent, nowMs: Long, utcOffsetMin: Int, onClear: () -> Unit) {
    val t = timeTones()
    val future = event.future || event.tsMs > nowMs
    val chip = when {
        !event.ok -> "Fallo"
        future -> "Programado"
        else -> "Hecho"
    }
    val chipBg = when {
        !event.ok -> t.alert.copy(alpha = 0.10f)
        future -> t.rail.copy(alpha = 0.6f)
        else -> t.accent.copy(alpha = 0.10f)
    }
    val chipFg = when {
        !event.ok -> t.alert
        future -> t.muted
        else -> t.accent
    }

    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    hhmm(event.tsMs + utcOffsetMin * 60_000L),
                    style = TextStyle(
                        fontFamily = Mono, fontSize = 19.sp, fontWeight = FontWeight.Medium,
                        letterSpacing = (-0.01).em, color = t.ink,
                    ),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    titleOf(event.kind),
                    style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = t.ink),
                )
            }
            if (event.detail.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    event.detail,
                    style = TextStyle(fontSize = 12.5.sp, lineHeight = 19.sp, color = t.muted),
                )
            }
            Spacer(Modifier.height(9.dp))
            Text(
                relativeTo(event.tsMs, nowMs).uppercase(),
                style = eyebrow(t.ghost, 8.5f),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(horizontalAlignment = Alignment.End) {
            Box(
                Modifier
                    .height(22.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(chipBg)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(chip.uppercase(), style = eyebrow(chipFg, 8f))
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "CERRAR",
                modifier = Modifier.clickable(onClick = onClear),
                style = eyebrow(t.faint, 8f),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend() {
    val t = timeTones()
    Column {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LegendItem(GlyphShape.DOT, t.ink, "Muestra")
            LegendItem(GlyphShape.DIAMOND, t.accent, "LoRa ↑ / ↓")
            LegendItem(GlyphShape.SQUARE, t.ink, "LSTM")
            LegendItem(GlyphShape.RING, t.faint, "Reloj")
            LegendItem(GlyphShape.DOT, t.alert, "Fallo")
            LegendItem(GlyphShape.HOLLOW, t.future, "Programado")
        }
        Spacer(Modifier.height(13.dp))
        Text(
            "ARRASTRA LA LÍNEA · PELLIZCA PARA ACERCAR · TOCA UNA MARCA",
            style = eyebrow(t.ghost, 8.5f),
        )
    }
}

private enum class GlyphShape { DOT, DIAMOND, SQUARE, RING, HOLLOW }

@Composable
private fun LegendItem(shape: GlyphShape, color: Color, label: String) {
    val t = timeTones()
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (shape) {
            GlyphShape.DOT -> Box(Modifier.size(7.dp).background(color, CircleShape))
            GlyphShape.DIAMOND -> Box(Modifier.size(8.dp).rotate(45f).background(color))
            GlyphShape.SQUARE -> Box(Modifier.size(9.dp).background(color, RoundedCornerShape(2.dp)))
            GlyphShape.RING -> Box(
                Modifier.size(9.dp).border(1.5.dp, color, CircleShape),
            )
            GlyphShape.HOLLOW -> Box(
                Modifier.size(9.dp).border(1.5.dp, color, RoundedCornerShape(2.dp)),
            )
        }
        Spacer(Modifier.width(7.dp))
        Text(label, style = TextStyle(fontSize = 11.5.sp, color = t.muted))
    }
}

internal fun titleOf(kind: EventKind): String = when (kind) {
    EventKind.SAMPLE -> "Lectura de sensores"
    EventKind.LORA_UP -> "Uplink LoRa"
    EventKind.LORA_DOWN -> "Downlink LoRa"
    EventKind.LSTM -> "Inferencia LSTM"
    EventKind.SYNC -> "Sincronización de reloj"
    EventKind.BOOT -> "Arranque de la estación"
}

/** "hace 12 min" / "en 3 h", from the station's own clock. */
internal fun relativeTo(tsMs: Long, nowMs: Long): String {
    val delta = tsMs - nowMs
    val ahead = delta > 0
    val min = kotlin.math.abs(delta) / 60_000L
    val body = when {
        min < 1 -> "menos de 1 min"
        min < 60 -> "$min min"
        min < 1440 -> "${min / 60} h"
        else -> "${min / 1440} d"
    }
    return if (ahead) "en $body" else "hace $body"
}
