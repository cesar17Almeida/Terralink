package com.astralink.terralink.ui.components.soilprobe

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.astralink.terralink.sensors.AQUACHECK_MIN_PERIOD_S
import com.astralink.terralink.ui.components.TerraIcons

/** Pass-to-pass periods the technician can pick. Below a minute the probe repeats
 *  its last reading (datasheet), so those are for watching the link, not the soil. */
val PROBE_PERIOD_OPTIONS_S = listOf(10, 30, 60, 120, 300)
const val PROBE_PERIOD_DEFAULT_S = AQUACHECK_MIN_PERIOD_S

/** "cada 30 s", "cada 2 min"; with [note], a flag on the periods the probe cannot honour. */
fun periodLabel(s: Int, note: Boolean = false): String {
    val base = if (s >= 60 && s % 60 == 0) "cada ${s / 60} min" else "cada $s s"
    return if (note && s < AQUACHECK_MIN_PERIOD_S) "$base · repite la última" else base
}

/**
 * The live card: a header naming the two series, the probe diagram beside one row
 * per depth, and a footer that says what the probe is doing right now.
 */
@Composable
fun SoilProbeCard(
    rows: List<DepthRowModel>,
    selected: Int?,
    onSelect: (Int) -> Unit,
    status: String,
    statusIsError: Boolean,
    hint: String,
    tick: Int,
    periodS: Int,
    onPeriodChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(cs.surfaceContainerLowest)
            .border(1.dp, cs.outlineVariant, shape),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("HUMEDAD · SFU (0 AIRE, 100 AGUA)", style = microLabelStyle())
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(8.dp).background(TemperatureBarColor, RoundedCornerShape(2.dp)))
                Text("TEMP.", style = microLabelStyle())
            }
        }
        HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.5f))

        Row(Modifier.weight(1f).fillMaxWidth()) {
            ProbeIllustration(
                count = rows.size,
                selected = selected,
                modifier = Modifier.width(76.dp).fillMaxHeight(),
            )
            VerticalDivider(color = cs.outlineVariant.copy(alpha = 0.5f))
            DepthRows(rows, selected, onSelect, Modifier.weight(1f).fillMaxHeight())
        }

        HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.5f))
        Row(
            modifier = Modifier.fillMaxWidth().background(cs.surfaceContainerLow)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (statusIsError) cs.error else cs.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.outline,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                ReadingCounter(tick)
                PeriodPicker(periodS, onPeriodChange)
            }
        }
    }
}

/** "lectura 003", breathing slowly so a stalled stream is visibly different from a live one. */
@Composable
private fun ReadingCounter(tick: Int) {
    val breathe by rememberInfiniteTransition(label = "breathe").animateFloat(
        0.5f, 1f, infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "alpha",
    )
    Text(
        text = "lectura " + tick.toString().padStart(3, '0'),
        style = MaterialTheme.typography.bodySmall.copy(
            fontWeight = FontWeight.Medium, fontFeatureSettings = TABULAR_NUMS,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.graphicsLayer { alpha = breathe },
    )
}

@Composable
private fun PeriodPicker(periodS: Int, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { open = true }
                .padding(start = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = periodLabel(periodS),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Icon(
                imageVector = TerraIcons.ExpandMore,
                contentDescription = "Cambiar la pausa entre lecturas",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            PROBE_PERIOD_OPTIONS_S.forEach { s ->
                DropdownMenuItem(
                    text = { Text(periodLabel(s, note = true)) },
                    onClick = { onChange(s); open = false },
                    trailingIcon = if (s == periodS) {
                        {
                            Icon(
                                TerraIcons.Check, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp),
                            )
                        }
                    } else null,
                )
            }
        }
    }
}
