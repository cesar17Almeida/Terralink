// Header of the lifecycle screen: where you are, what the station is doing right
// now, and which of its series the track is drawing.
package com.astralink.terralink.ui.components.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** One selectable series: a sensor's channel, as the chips name it. */
data class SeriesChip(val id: String, val label: String, val unit: String)

@Composable
fun LifecycleHeader(
    stationName: String,
    title: String,
    clockLabel: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = timeTones()
    Column(modifier) {
        Row(Modifier.height(30.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .offset(x = (-8).dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Text("‹", fontSize = 28.sp, color = t.ink)
            }
            Spacer(Modifier.width(12.dp))
            Text(
                "ESTACIÓN · ${stationName.uppercase()}",
                style = eyebrow(t.faint, 9f),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(
                title,
                modifier = Modifier.weight(1f),
                style = TextStyle(
                    fontSize = 27.sp, fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.025).em, lineHeight = 30.sp, color = t.ink,
                ),
            )
            Text(
                clockLabel,
                modifier = Modifier.padding(bottom = 4.dp),
                style = TextStyle(fontFamily = Mono, fontSize = 10.sp, color = t.faint),
            )
        }
    }
}

@Composable
fun SeriesChips(
    chips: List<SeriesChip>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = timeTones()
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        chips.forEach { c ->
            val on = c.id == selectedId
            val shape = RoundedCornerShape(percent = 50)
            Box(
                Modifier
                    .height(30.dp)
                    .background(if (on) t.accent else Color.Transparent, shape)
                    .border(1.dp, if (on) t.accent else t.hairline, shape)
                    .clickable { onSelect(c.id) }
                    .padding(horizontal = 13.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    c.label.uppercase(),
                    style = TextStyle(
                        fontFamily = Mono, fontSize = 8.5.sp, letterSpacing = 0.1.em,
                        color = if (on) MaterialTheme.colorScheme.onPrimary else t.muted,
                    ),
                )
            }
        }
    }
}

/** The "what is it doing / when does it wake" pair, divided like the design. */
@Composable
fun StateRow(
    stateNow: String,
    nextLabel: String,
    nextValue: String,
    modifier: Modifier = Modifier,
) {
    val t = timeTones()
    Column(modifier.fillMaxWidth()) {
        HorizontalDivider(color = t.hairline)
        Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Field("Estado", stateNow, Modifier.weight(1f))
            Box(Modifier.width(1.dp).height(28.dp).background(t.hairline))
            Field(nextLabel, nextValue, Modifier.weight(1.3f).padding(start = 16.dp))
        }
        HorizontalDivider(color = t.hairline)
    }
}

@Composable
private fun Field(label: String, value: String, modifier: Modifier = Modifier) {
    val t = timeTones()
    Column(modifier) {
        Text(label.uppercase(), style = eyebrow(t.faint))
        Spacer(Modifier.height(6.dp))
        Text(
            value,
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = t.ink),
        )
    }
}
