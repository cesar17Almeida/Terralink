// The row above the track (unit + zoom presets) and the row below it (the day's
// numbers). Small, but they are what makes the track navigable without a manual.
package com.astralink.terralink.ui.components.timeline

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

@Composable
fun TrackControls(
    unitLabel: String,
    activeZoom: Zoom?,
    mode: TrackMode,
    onZoom: (Zoom) -> Unit,
    onMode: (TrackMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = timeTones()
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(unitLabel.uppercase(), modifier = Modifier.weight(1f), style = eyebrow(t.faint))
        SmallToggle(
            options = listOf("Pista" to TrackMode.PISTA, "Carriles" to TrackMode.CARRILES),
            selected = mode,
            onSelect = onMode,
            width = 54.dp,
        )
        Spacer(Modifier.width(8.dp))
        SmallToggle(
            options = Zoom.entries.map { it.label to it },
            selected = activeZoom,
            onSelect = onZoom,
            width = 28.dp,
        )
    }
}

@Composable
private fun <T> SmallToggle(
    options: List<Pair<String, T>>,
    selected: T?,
    onSelect: (T) -> Unit,
    width: androidx.compose.ui.unit.Dp,
) {
    val t = timeTones()
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { (label, value) ->
            val on = value == selected
            val shape = RoundedCornerShape(6.dp)
            Box(
                Modifier
                    .width(width)
                    .height(22.dp)
                    .background(if (on) t.ink else Color.Transparent, shape)
                    .border(1.dp, if (on) t.ink else t.hairline, shape)
                    .clickable { onSelect(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = TextStyle(
                        fontFamily = Mono, fontSize = 8.5.sp, letterSpacing = 0.08.em,
                        color = if (on) MaterialTheme.colorScheme.surface else t.faint,
                    ),
                )
            }
        }
    }
}

/** The trailing 24 h in three numbers, the way the design closes the screen. */
@Composable
fun DayFooter(
    wakes: Int,
    asleep: String,
    lora: String,
    loraHealthy: Boolean,
    modifier: Modifier = Modifier,
) {
    val t = timeTones()
    Column(modifier.fillMaxWidth()) {
        HorizontalDivider(color = t.hairline)
        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Stat(wakes.toString(), "Despertares 24 h", t.ink, Modifier.weight(1f))
            Box(Modifier.width(1.dp).height(26.dp).background(t.hairline))
            Stat(asleep, "Inactiva", t.ink, Modifier.weight(1f).padding(start = 16.dp))
            Box(Modifier.width(1.dp).height(26.dp).background(t.hairline))
            Stat(lora, "Envíos LoRa", if (loraHealthy) t.ink else t.alert,
                Modifier.weight(1f).padding(start = 16.dp))
        }
    }
}

@Composable
private fun Stat(value: String, label: String, color: Color, modifier: Modifier = Modifier) {
    val t = timeTones()
    Column(modifier) {
        Text(value, style = monoValue(color))
        Spacer(Modifier.height(5.dp))
        Text(label.uppercase(), style = eyebrow(t.faint))
    }
}
