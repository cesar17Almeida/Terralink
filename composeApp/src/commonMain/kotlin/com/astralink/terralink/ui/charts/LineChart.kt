package com.astralink.terralink.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** A single line in the chart: a label, a color, and timestamped points. */
data class ChartSeries(
    val label: String,
    val color: Color,
    val points: List<Pair<Long, Float>>,  // (ts_ms, value)
)

/**
 * Lightweight line chart drawn directly in Compose Canvas, no external
 * dependencies. Auto-scales both axes to the data; renders an empty state
 * if `series` is empty or every series is empty.
 *
 * For the SyncScreen use case we render up to 6 depth lines simultaneously
 * for a single kind (humidity OR temperature) -- legible without
 * interactivity. The chart adds axes baselines and a flow-row legend
 * underneath.
 */
@Composable
fun LineChart(
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    yLabel: String = "",
) {
    val nonEmpty = series.filter { it.points.isNotEmpty() }
    if (nonEmpty.isEmpty()) {
        EmptyChart(modifier = modifier)
        return
    }
    val allPoints = nonEmpty.flatMap { it.points }
    val xMin = allPoints.minOf { it.first }
    val xMax = allPoints.maxOf { it.first }
    val yMinRaw = allPoints.minOf { it.second }
    val yMaxRaw = allPoints.maxOf { it.second }
    // 5% headroom on the Y axis so the topmost point isn't glued to the edge.
    val yPad = ((yMaxRaw - yMinRaw) * 0.05f).takeIf { it > 0 } ?: 0.5f
    val yMin = yMinRaw - yPad
    val yMax = yMaxRaw + yPad
    val dx = (xMax - xMin).coerceAtLeast(1L)
    val dy = (yMax - yMin).coerceAtLeast(0.001f)

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            val axisColor = MaterialTheme.colorScheme.outlineVariant
            Canvas(modifier = Modifier.fillMaxWidth().height(200.dp).padding(8.dp)) {
                val w = size.width
                val h = size.height
                // Baseline + left axis.
                drawLine(
                    color = axisColor,
                    start = Offset(0f, h),
                    end = Offset(w, h),
                    strokeWidth = 1f,
                )
                drawLine(
                    color = axisColor,
                    start = Offset(0f, 0f),
                    end = Offset(0f, h),
                    strokeWidth = 1f,
                )
                for (s in nonEmpty) {
                    val path = Path()
                    s.points.forEachIndexed { i, (ts, v) ->
                        val x = ((ts - xMin).toFloat() / dx) * w
                        val y = (1f - (v - yMin) / dy) * h
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(
                        path = path,
                        color = s.color,
                        style = Stroke(
                            width = 2.5f,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        ),
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${formatNumber(yMinRaw)}—${formatNumber(yMaxRaw)} $yLabel",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${allPoints.size} puntos",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(8.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (s in nonEmpty) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(10.dp).background(s.color, CircleShape))
                    Spacer(Modifier.width(4.dp))
                    Text(text = s.label, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun EmptyChart(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Sin datos para mostrar",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatNumber(v: Float): String {
    val abs = if (v < 0) -v else v
    return when {
        abs >= 100 -> v.toInt().toString()
        abs >= 10 -> ((v * 10).toInt() / 10f).toString()
        else -> ((v * 100).toInt() / 100f).toString()
    }
}

/**
 * Six-step palette for the six depth lines. Cool blues for moisture,
 * warm oranges for temperature -- generated by interpolation from the
 * Material 3 primary / tertiary swatches but hand-tuned for legibility.
 */
val MoistureDepthColors: List<Color> = listOf(
    Color(0xFF60A5FA), Color(0xFF3B82F6), Color(0xFF2563EB),
    Color(0xFF1D4ED8), Color(0xFF1E40AF), Color(0xFF1E3A8A),
)

val TemperatureDepthColors: List<Color> = listOf(
    Color(0xFFFCA5A5), Color(0xFFF87171), Color(0xFFEF4444),
    Color(0xFFDC2626), Color(0xFFB91C1C), Color(0xFF991B1B),
)
