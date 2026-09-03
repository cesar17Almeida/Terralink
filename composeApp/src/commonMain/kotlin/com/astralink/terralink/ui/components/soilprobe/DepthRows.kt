package com.astralink.terralink.ui.components.soilprobe

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One sensor of the probe as the screen shows it. Null values render as a dash. */
data class DepthRowModel(val depthCm: Int, val moisture: Float?, val temperatureC: Float?)

/** Bars settle like a needle: fast out, a long soft landing. */
private val BarEasing = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)
private const val BAR_MS = 1600

/** The right-hand column: one row per sensor, tap to focus it (the others fade). */
@Composable
fun DepthRows(
    rows: List<DepthRowModel>,
    selected: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        rows.forEachIndexed { i, row ->
            DepthRow(
                row = row,
                focused = selected == i,
                dimmed = selected != null && selected != i,
                onClick = { onSelect(i) },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun DepthRow(
    row: DepthRowModel,
    focused: Boolean,
    dimmed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val bg by animateColorAsState(
        if (focused) cs.surfaceContainerLow else cs.surfaceContainerLowest, tween(220), label = "rowBg",
    )
    val rowAlpha by animateFloatAsState(if (dimmed) 0.42f else 1f, tween(220), label = "rowAlpha")
    val moistureFill by animateFloatAsState(
        ((row.moisture ?: 0f) / MOISTURE_SCALE_MAX).coerceIn(0f, 1f),
        tween(BAR_MS, easing = BarEasing), label = "moisture",
    )
    val tempFill by animateFloatAsState(
        (((row.temperatureC ?: TEMPERATURE_SCALE_MIN) - TEMPERATURE_SCALE_MIN) /
            (TEMPERATURE_SCALE_MAX - TEMPERATURE_SCALE_MIN)).coerceIn(0f, 1f),
        tween(BAR_MS, easing = BarEasing), label = "temperature",
    )

    Box(
        modifier = modifier
            .background(bg)
            .clickable(onClick = onClick)
            .graphicsLayer { alpha = rowAlpha },
    ) {
        Column(
            modifier = Modifier.fillMaxHeight().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${row.depthCm} CM", style = microLabelStyle())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = row.temperatureC?.let(::formatOneDecimal) ?: "—",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = TABULAR_NUMS,
                        ),
                        color = if (row.temperatureC != null) TemperatureTextColor else cs.outline,
                    )
                    Text(
                        text = " °C",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = cs.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(9.dp))
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(modifier = Modifier.widthIn(min = 88.dp)) {
                    Text(
                        text = row.moisture?.let(::formatOneDecimal) ?: "—",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontSize = 34.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold,
                            letterSpacing = (-0.7).sp, fontFeatureSettings = TABULAR_NUMS,
                        ),
                        color = if (row.moisture != null) cs.onSurface else cs.outline,
                        modifier = Modifier.alignByBaseline(),
                    )
                    Text(
                        text = "SFU",
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(start = 3.dp).alignByBaseline(),
                    )
                }
                Column(
                    modifier = Modifier.weight(1f).padding(bottom = 3.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Bar(fill = moistureFill, height = 8.dp, color = cs.primary, track = cs.surfaceContainerHigh)
                    Bar(fill = tempFill, height = 4.dp, color = TemperatureBarColor, track = cs.surfaceContainerHigh)
                }
            }
        }
        HorizontalDivider(
            modifier = Modifier.align(Alignment.BottomCenter),
            color = cs.outlineVariant.copy(alpha = 0.5f),
        )
    }
}

@Composable
private fun Bar(fill: Float, height: androidx.compose.ui.unit.Dp, color: Color, track: Color) {
    Box(
        modifier = Modifier.fillMaxWidth().height(height).clip(CircleShape).background(track),
    ) {
        Box(Modifier.fillMaxWidth(fill).fillMaxHeight().background(color, CircleShape))
    }
}
