// Predicted against measured, on one time axis, with the residual as its own panel
// underneath. Two panels, never two y-scales on one plot: the error is a different
// quantity from the moisture, and stacking their axes would invent a relationship.
package com.astralink.terralink.ui.components.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.astralink.terralink.timeline.Paired
import kotlin.math.abs
import kotlin.math.max

val ACCURACY_CHART_HEIGHT = 268.dp

private val PLOT_H = 176.dp
private val RESID_TOP = 194.dp
private val RESID_H = 46.dp
private val AXIS_TOP = 246.dp
private val PLOT_PAD = 14.dp

/**
 * [pairs] must be sorted by target hour. [onScrub] reports the hour under the
 * finger so the caller can show a readout above the chart -- a chart you can
 * question point by point is the difference between "the model is good" and "the
 * model was 0.04 low at 3 in the morning".
 */
@Composable
fun AccuracyChart(
    pairs: List<Paired>,
    utcOffsetMin: Int,
    selectedIndex: Int?,
    onScrub: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = timeTones()
    val measurer = rememberTextMeasurer()
    val hapticFeedback = LocalHapticFeedback.current
    val haptics = remember(hapticFeedback) { TimelineHaptics(hapticFeedback) }
    var lastIndex by remember { mutableStateOf<Int?>(null) }

    if (pairs.isEmpty()) {
        EmptyPlot(modifier)
        return
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(ACCURACY_CHART_HEIGHT)
            .pointerInput(pairs) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    fun report(change: PointerInputChange, frameMs: Long) {
                        val w = size.width.toFloat()
                        val i = indexAt(change.position.x, w, pairs.size)
                        if (i != lastIndex) {
                            lastIndex = i
                            haptics.minorTick(frameMs)
                            onScrub(i)
                        }
                    }
                    report(down, down.uptimeMillis)
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.firstOrNull { it.pressed } ?: break
                        report(pressed, pressed.uptimeMillis)
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxWidth().height(ACCURACY_CHART_HEIGHT)) {
            val plot = AccuracyDraw(this, t, measurer, pairs, utcOffsetMin, selectedIndex)
            plot.drawPlot()
            plot.drawRange()
            plot.drawResiduals()
            plot.drawAxis()
        }
    }
}

@Composable
private fun EmptyPlot(modifier: Modifier) {
    val t = timeTones()
    val measurer = rememberTextMeasurer()
    Canvas(modifier.fillMaxWidth().height(ACCURACY_CHART_HEIGHT)) {
        val y = size.height / 2f
        drawLine(t.hairline, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        drawText(
            measurer, "Sin horas comparables todavía",
            topLeft = Offset(0f, y + 10f),
            style = TextStyle(fontFamily = Mono, fontSize = 10.sp, color = t.faint),
        )
    }
}

private fun indexAt(x: Float, width: Float, count: Int): Int {
    if (count <= 1) return 0
    val k = (x / max(width, 1f)).coerceIn(0f, 1f)
    return (k * (count - 1)).toInt().coerceIn(0, count - 1)
}

private fun fmt3(v: Double): String = com.astralink.terralink.timeline.fmtDecimals(v, 3)

/** dd/MM from an epoch instant, via the civil-from-days algorithm (no date API). */
internal fun dayMonth(ms: Long): String {
    val day = floorDivDays(ms)
    val z = day + 719_468
    val era = if (z >= 0) z / 146_097 else (z - 146_096) / 146_097
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val mon = if (mp < 10) mp + 3 else mp - 9
    return "${d.toString().padStart(2, '0')}/${mon.toString().padStart(2, '0')}"
}

private fun floorDivDays(ms: Long): Long {
    val q = ms / 86_400_000L
    return if (ms % 86_400_000L != 0L && ms < 0) q - 1 else q
}

private class AccuracyDraw(
    val scope: DrawScope,
    val t: TimeTones,
    val measurer: TextMeasurer,
    val pairs: List<Paired>,
    val utcOffsetMin: Int,
    val selected: Int?,
) {
    val w: Float get() = scope.size.width
    fun dp(v: androidx.compose.ui.unit.Dp): Float = with(scope) { v.toPx() }

    private val values = pairs.flatMap { listOf(it.predicted, it.actual) }
    val lo = values.min()
    val hi = values.max()
    private val span = (hi - lo).takeIf { it > 1e-9 } ?: 1.0
    private val maxAbsError = pairs.maxOf { abs(it.error) }.takeIf { it > 1e-9 } ?: 1.0

    fun x(i: Int): Float =
        if (pairs.size == 1) w / 2f else i.toFloat() / (pairs.size - 1) * w

    fun y(v: Double): Float {
        val h = dp(PLOT_H)
        val pad = dp(PLOT_PAD)
        val k = ((v - lo) / span).coerceIn(0.0, 1.0)
        return h - pad - (k * (h - pad * 2)).toFloat()
    }

    fun drawPlot() {
        // The gap between the two lines, filled: the error is the subject of this
        // chart, so it gets a shape rather than being left as negative space.
        if (pairs.size > 1) {
            val ribbon = Path()
            pairs.forEachIndexed { i, p ->
                val px = x(i); val py = y(p.predicted)
                if (i == 0) ribbon.moveTo(px, py) else ribbon.lineTo(px, py)
            }
            for (i in pairs.indices.reversed()) ribbon.lineTo(x(i), y(pairs[i].actual))
            ribbon.close()
            scope.drawPath(ribbon, t.ink.copy(alpha = 0.06f))
        }

        line(pairs.map { it.actual }, MeasuredColor, dashed = false)
        line(pairs.map { it.predicted }, PredictedColor, dashed = true)

        // Direct labels beat a legend lookup: two series, two labels, at the end of
        // each line where the eye already is.
        pairs.lastOrNull()?.let { last ->
            val lx = (x(pairs.size - 1) - dp(34.dp)).coerceAtLeast(dp(2.dp))
            label("real", lx, y(last.actual) - dp(13.dp), MeasuredColor)
            label("LSTM", lx, y(last.predicted) - dp(13.dp), PredictedColor)
        }

        selected?.let { i ->
            val p = pairs[i]
            scope.drawLine(
                t.ghost, Offset(x(i), 0f), Offset(x(i), dp(PLOT_H)), strokeWidth = 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 4f)),
            )
            dot(x(i), y(p.actual), MeasuredColor)
            dot(x(i), y(p.predicted), PredictedColor)
        }
    }

    private fun line(series: List<Double>, color: Color, dashed: Boolean) {
        if (series.size < 2) {
            series.firstOrNull()?.let { dot(x(0), y(it), color) }
            return
        }
        val path = Path()
        series.forEachIndexed { i, v ->
            val px = x(i); val py = y(v)
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        scope.drawPath(
            path, color,
            style = Stroke(
                width = dp(2.dp), cap = StrokeCap.Round, join = StrokeJoin.Round,
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(dp(5.dp), dp(4.dp))) else null,
            ),
        )
    }

    /** A marker with a surface ring, so overlapping points stay countable. */
    private fun dot(cx: Float, cy: Float, color: Color) {
        scope.drawCircle(t.sheet, dp(5.5.dp), Offset(cx, cy))
        scope.drawCircle(color, dp(4.dp), Offset(cx, cy))
    }

    /**
     * The residual panel: signed error around a zero baseline, diverging on the
     * sign, so "the model runs wet" is visible as a shape rather than something you
     * have to work out from two lines.
     */
    fun drawResiduals() {
        val top = dp(RESID_TOP)
        val h = dp(RESID_H)
        val zero = top + h / 2f
        scope.drawLine(t.ghost, Offset(0f, zero), Offset(w, zero), strokeWidth = 1f)
        // The panel's title carries the sign convention: a diverging bar chart is
        // only readable if "up" is named.
        label("error · arriba = el LSTM predijo de más", dp(2.dp), top - dp(4.dp), t.faint)

        val slot = if (pairs.size > 1) w / pairs.size else w
        val barW = (slot - dp(2.dp)).coerceIn(dp(2.dp), dp(14.dp))   // 2 dp of surface between bars
        pairs.forEachIndexed { i, p ->
            val hgt = (abs(p.error) / maxAbsError * (h / 2f - dp(4.dp))).toFloat()
            val cx = x(i)
            val color = if (p.error >= 0) OverPredictColor else UnderPredictColor
            val alpha = if (selected == null || selected == i) 1f else 0.35f
            val yTop = if (p.error >= 0) zero - hgt else zero
            scope.drawRoundRect(
                color = color.copy(alpha = alpha),
                topLeft = Offset(cx - barW / 2f, yTop),
                size = Size(barW, hgt.coerceAtLeast(1f)),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(dp(2.dp)),
            )
        }
    }

    fun drawAxis() {
        val top = dp(AXIS_TOP)
        // An hour on its own is ambiguous the moment the scored hours span more than
        // a day -- and they usually do, since a forecast is scored a day after it ran.
        val spansDays = pairs.last().targetMs - pairs.first().targetMs >= 86_400_000L
        // Three labels, not one per point: the axis says where you are, the readout
        // says what is there.
        val marks = listOf(0, pairs.size / 2, pairs.size - 1).distinct()
        marks.forEach { i ->
            val local = pairs[i].targetMs + utcOffsetMin * 60_000L
            val text = if (spansDays) "${dayMonth(local)} ${hhmm(local)}" else hhmm(local)
            val style = TextStyle(fontFamily = Mono, fontSize = 9.sp, letterSpacing = 0.04.em, color = t.faint)
            val laid = measurer.measure(text, style)
            val cx = (x(i) - laid.size.width / 2f).coerceIn(0f, w - laid.size.width)
            scope.drawText(laid, topLeft = Offset(cx, top))
        }
    }

    /** The plot's own extent, so the two lines can be read without a full axis. */
    fun drawRange() {
        label(fmt3(hi), dp(2.dp), y(hi) - dp(12.dp), t.ghost)
        label(fmt3(lo), dp(2.dp), y(lo) + dp(3.dp), t.ghost)
    }

    private fun label(text: String, x: Float, y: Float, color: Color) {
        scope.drawText(
            measurer, text, topLeft = Offset(x, y),
            style = TextStyle(fontFamily = Mono, fontSize = 9.sp, letterSpacing = 0.08.em, color = color),
        )
    }
}
