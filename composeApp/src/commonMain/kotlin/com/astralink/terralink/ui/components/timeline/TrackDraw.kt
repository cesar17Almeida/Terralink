// How one frame of the track is painted: the value curve, the sleep rail, the event
// marks, the density band, the ruler and the "ahora" line. Split from the gesture
// side so each file is about one thing -- what the track shows, and how it moves.
package com.astralink.terralink.ui.components.timeline

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.astralink.terralink.timeline.EventKind
import com.astralink.terralink.timeline.StationEvent
import kotlin.math.abs
import kotlin.math.min

// PISTA bands, in dp from the top of the track.
private val CURVE_H = 240.dp
private val RAIL_TOP = 246.dp
private val RAIL_H = 34.dp
private val DENSITY_TOP = 286.dp
private val DENSITY_H = 14.dp
private val RULER_TOP = 306.dp

// CARRILES bands.
private val LANE_CURVE_H = 118.dp
private val LANE_TOP = 124.dp
private val LANE_H = 26.dp     // 11 dp of label, then the mark row under it
private val FC_TOP = 256.dp
private val FC_H = 48.dp

private val LANE_ORDER = listOf(
    EventKind.SAMPLE to "Muestra",
    EventKind.LORA_UP to "LoRa ↑",
    EventKind.LORA_DOWN to "LoRa ↓",
    EventKind.LSTM to "LSTM",
    EventKind.SYNC to "Reloj",
)


// --- drawing -----------------------------------------------------------------

internal data class Domain(val min: Double, val max: Double) {
    val span: Double get() = (max - min).takeIf { it > 1e-9 } ?: 1.0
}

internal fun valueDomain(s: TrackSeries): Domain {
    val values = buildList {
        addAll(s.measured.map { it.value })
        addAll(s.forecast.map { it.value })
        s.band.forEach { add(it.second); add(it.third) }
        s.reference?.let { add(it) }
    }
    if (values.isEmpty()) return Domain(0.0, 1.0)
    val lo = values.min()
    val hi = values.max()
    val pad = ((hi - lo) * 0.12).takeIf { it > 1e-9 } ?: (abs(hi) * 0.1 + 0.05)
    return Domain(lo - pad, hi + pad)
}

/** Everything one frame of the track needs, so the band drawing stays readable. */
internal class TrackDraw(
    val scope: DrawScope,
    val vp: TimelineViewport,
    val t: TimeTones,
    val measurer: TextMeasurer,
    val nowMs: Long,
    val domain: Domain,
    val hits: MutableList<MarkHit>,
    val selected: StationEvent?,
) {
    val density: Density get() = scope
    val w: Float get() = scope.size.width

    fun dp(v: androidx.compose.ui.unit.Dp): Float = with(density) { v.toPx() }
    fun x(ts: Long): Float = vp.xOf(ts)

    fun y(value: Double, top: Float, height: Float, pad: Float): Float {
        val k = ((value - domain.min) / domain.span).coerceIn(0.0, 1.0)
        return top + height - pad - (k * (height - pad * 2)).toFloat()
    }

    // --- PISTA ---------------------------------------------------------------

    fun drawPista(data: TrackData) {
        val h = dp(CURVE_H)
        val pad = dp(18.dp)
        drawSeries(data.series, top = 0f, height = h, pad = pad, withBand = true, withFill = true)
        drawSleepRail(data, top = dp(RAIL_TOP), height = dp(RAIL_H))
        drawMarks(data, centerY = dp(RAIL_TOP) + dp(16.dp), boxH = dp(34.dp))
        drawDensity(data, top = dp(DENSITY_TOP), height = dp(DENSITY_H))
    }

    // --- CARRILES ------------------------------------------------------------

    fun drawCarriles(data: TrackData) {
        val h = dp(LANE_CURVE_H)
        // The reference line runs through both panels (it is the same value) but is
        // named only once, under the forecast that produced it.
        drawSeries(
            data.series.copy(forecast = emptyList(), band = emptyList(), referenceLabel = ""),
            top = 0f, height = h, pad = dp(14.dp), withBand = false, withFill = true,
        )
        label("Medido", dp(10.dp), dp(4.dp), t.muted, 9f)

        LANE_ORDER.forEachIndexed { i, (kind, name) ->
            val top = dp(LANE_TOP) + i * dp(LANE_H)
            val baseline = top + dp(17.dp)
            scope.drawLine(t.lane, Offset(0f, baseline + dp(9.dp)), Offset(w, baseline + dp(9.dp)),
                strokeWidth = 1f)
            label(name, dp(2.dp), top, t.faint, 9f)
            val lane = data.events.filter { it.kind == kind }
            if (kind == EventKind.SAMPLE && vp.pxPerMs < DOT_VISIBLE_PX_PER_MS) {
                drawDensityInto(lane, top + dp(12.dp), dp(11.dp))
            } else {
                lane.forEach { mark(it, baseline, dp(18.dp)) }
            }
        }

        // The forecast gets a band of its own here: the same hours, read as one
        // more channel rather than as the continuation of the measured curve.
        val fcTop = dp(FC_TOP)
        val fcH = dp(FC_H)
        val fc = TrackSeries(
            measured = emptyList(), forecast = data.series.forecast,
            band = data.series.band, reference = data.series.reference,
            referenceLabel = data.series.referenceLabel,
        )
        drawSeries(fc, top = fcTop, height = fcH, pad = dp(9.dp), withBand = true, withFill = false)
        label("Pronóstico LSTM", dp(10.dp), fcTop + dp(2.dp), t.muted, 9f)
    }

    // --- shared bands --------------------------------------------------------

    private fun drawSeries(
        s: TrackSeries, top: Float, height: Float, pad: Float,
        withBand: Boolean, withFill: Boolean,
    ) {
        val range = vp.visibleRange()

        s.reference?.let { ref ->
            val ry = y(ref, top, height, pad)
            scope.drawLine(
                color = t.alert.copy(alpha = 0.55f),
                start = Offset(0f, ry), end = Offset(w, ry), strokeWidth = 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 4f)),
            )
            if (s.referenceLabel.isNotBlank()) {
                // Below the line normally, above it when the line sits near the floor
                // of its own band -- otherwise the label lands on the next band down.
                val below = ry + dp(3.dp)
                val ly = if (below + dp(12.dp) > top + height) ry - dp(13.dp) else below
                label(s.referenceLabel, dp(10.dp), ly, t.alert, 9f)
            }
        }

        if (withBand && s.band.isNotEmpty()) {
            val band = s.band.filter { it.first in range }
            if (band.size > 1) {
                val p = Path()
                band.forEachIndexed { i, (ts, _, hi) ->
                    val px = x(ts); val py = y(hi, top, height, pad)
                    if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
                }
                for (i in band.indices.reversed()) {
                    val (ts, lo, _) = band[i]
                    p.lineTo(x(ts), y(lo, top, height, pad))
                }
                p.close()
                scope.drawPath(p, t.accent.copy(alpha = 0.10f))
            }
        }

        val measured = s.measured.filter { it.tsMs in range }
        if (measured.size > 1) {
            if (withFill) {
                val fill = Path()
                measured.forEachIndexed { i, pt ->
                    val px = x(pt.tsMs); val py = y(pt.value, top, height, pad)
                    if (i == 0) fill.moveTo(px, py) else fill.lineTo(px, py)
                }
                fill.lineTo(x(measured.last().tsMs), top + height)
                fill.lineTo(x(measured.first().tsMs), top + height)
                fill.close()
                scope.drawPath(fill, t.accent.copy(alpha = 0.08f))
            }
            scope.drawPath(
                pathOf(measured, top, height, pad), t.accent,
                style = Stroke(width = dp(1.8.dp), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }

        val forecast = s.forecast.filter { it.tsMs in range }
        if (forecast.size > 1) {
            scope.drawPath(
                pathOf(forecast, top, height, pad), t.accent.copy(alpha = 0.8f),
                style = Stroke(
                    width = dp(1.5.dp), cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(dp(4.dp), dp(4.dp))),
                ),
            )
        }
    }

    private fun pathOf(pts: List<TrackPoint>, top: Float, height: Float, pad: Float): Path {
        val p = Path()
        pts.forEachIndexed { i, pt ->
            val px = x(pt.tsMs); val py = y(pt.value, top, height, pad)
            if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
        }
        return p
    }

    /**
     * The sleep rail. What is drawn is the gap between two things that happened --
     * evidence the station was idle, not a claim it was in deep sleep, which the
     * app has no way to observe from outside.
     */
    private fun drawSleepRail(data: TrackData, top: Float, height: Float) {
        val barTop = top + dp(11.dp)
        val barH = dp(11.dp)
        // A hairline for the axis, and filled bars only where the station was idle:
        // drawing a full-width track behind them would make "asleep" and "awake" the
        // same shade of nearly-nothing.
        scope.drawLine(
            t.lane, Offset(0f, barTop + barH / 2f), Offset(w, barTop + barH / 2f), strokeWidth = 1f,
        )
        val range = vp.visibleRange()
        val radius = androidx.compose.ui.geometry.CornerRadius(barH / 2f)
        val inset = dp(1.dp)          // so back-to-back naps read as two, not one band
        var labelledUntil = -1f       // right edge of the last label drawn
        for (gap in data.events.sleepGaps()) {
            if (gap.last < range.first || gap.first > range.last) continue
            val x0 = x(gap.first) + inset
            val x1 = x(gap.last) - inset
            if (x1 - x0 < 3f) continue
            scope.drawRoundRect(t.rail, Offset(x0, barTop), Size(x1 - x0, barH), radius)
            // One label per bar, only where the bar is actually wide enough to hold
            // it and it won't land on the last one drawn: a row of overlapping
            // "1 h dormida" is noise, not a reading. The width test measures the
            // text rather than guessing at a dp threshold, so it holds at any zoom.
            val style = TextStyle(fontFamily = Mono, fontSize = 9.sp, letterSpacing = 0.08.em, color = t.faint)
            val duration = humanGap(gap.last - gap.first)
            // Try the full phrase, fall back to the bare duration, drop it if even
            // that won't fit -- a nap narrower than its own label says nothing.
            var laid = measurer.measure("$duration dormida", style)
            if (x1 - x0 < laid.size.width + dp(12.dp)) laid = measurer.measure(duration, style)
            if (x1 - x0 < laid.size.width + dp(8.dp)) continue
            val lx = (x0 + (x1 - x0 - laid.size.width) / 2f).coerceIn(0f, w - laid.size.width)
            if (lx < labelledUntil + dp(8.dp)) continue
            scope.drawText(laid, topLeft = Offset(lx, top + dp(26.dp)))
            labelledUntil = lx + laid.size.width
        }
    }

    private fun drawMarks(data: TrackData, centerY: Float, boxH: Float) {
        val range = vp.visibleRange()
        val showDots = vp.pxPerMs >= DOT_VISIBLE_PX_PER_MS
        data.events
            .filter { it.tsMs in range && (showDots || it.kind.isMajor()) }
            .forEach { mark(it, centerY, boxH) }
    }

    /** Sample marks past the point where they'd overlap become a density band --
     *  the design's "resolves into individual points as you zoom in". */
    private fun drawDensity(data: TrackData, top: Float, height: Float) {
        if (vp.pxPerMs >= DOT_VISIBLE_PX_PER_MS) return
        drawDensityInto(data.events.filter { it.kind == EventKind.SAMPLE }, top, height)
    }

    private fun drawDensityInto(events: List<StationEvent>, top: Float, height: Float) {
        if (events.isEmpty() || w <= 0f) return
        val bucket = dp(3.dp)
        val past = HashMap<Int, Int>()
        val future = HashMap<Int, Int>()
        for (e in events) {
            val px = x(e.tsMs)
            if (px < -bucket || px > w + bucket) continue
            val b = (px / bucket).toInt()
            val into = if (e.tsMs > nowMs || e.future) future else past
            into[b] = (into[b] ?: 0) + 1
        }
        // Scheduled samples stay visibly lighter than taken ones: the density band
        // has to keep reading as "measured to the left, planned to the right".
        fun paint(buckets: Map<Int, Int>, base: Float, step: Float, cap: Float) {
            for ((b, n) in buckets) {
                scope.drawRect(
                    t.ink.copy(alpha = min(cap, base + n * step)),
                    Offset(b * bucket, top), Size(dp(2.dp), height),
                )
            }
        }
        paint(past, 0.14f, 0.13f, 0.5f)
        paint(future, 0.06f, 0.05f, 0.18f)
    }

    /**
     * One event. The glyph says what it is, whether it is filled says whether it
     * has happened: the future is drawn as an outline of the past, which is what
     * makes one track read continuously across "ahora".
     */
    private fun mark(e: StationEvent, centerY: Float, boxH: Float) {
        val px = x(e.tsMs)
        if (px < -dp(20.dp) || px > w + dp(20.dp)) return
        val future = e.tsMs > nowMs || e.future
        val size = when (e.kind) {
            EventKind.SAMPLE -> dp(6.dp)
            EventKind.LORA_UP, EventKind.LORA_DOWN -> dp(8.dp)
            EventKind.LSTM -> dp(10.dp)
            else -> dp(9.dp)
        }
        val colour = when {
            !e.ok -> t.alert
            e.kind == EventKind.LORA_UP || e.kind == EventKind.LORA_DOWN -> t.accent
            e.kind == EventKind.SYNC -> t.faint
            else -> t.ink
        }
        val fill = if (future) Color.Transparent else colour
        val edge = if (future) t.future else colour
        val isSelected = selected sameAs e

        if (isSelected) {
            scope.drawCircle(t.accent.copy(alpha = 0.18f), size + dp(5.dp), Offset(px, centerY))
        }

        when (e.kind) {
            // A round dot for a reading, a diamond for a radio exchange (pointing up
            // for an uplink and down for a downlink), a square for a run of the model,
            // a ring for the clock.
            EventKind.SAMPLE, EventKind.SYNC -> {
                if (fill != Color.Transparent) scope.drawCircle(fill, size / 2f, Offset(px, centerY))
                scope.drawCircle(edge, size / 2f, Offset(px, centerY), style = Stroke(dp(1.5.dp)))
            }
            EventKind.LORA_UP, EventKind.LORA_DOWN -> {
                val dir = if (e.kind == EventKind.LORA_UP) -1f else 1f
                val p = Path().apply {
                    moveTo(px, centerY + dir * size * 0.62f)
                    lineTo(px + size * 0.55f, centerY)
                    lineTo(px, centerY - dir * size * 0.62f)
                    lineTo(px - size * 0.55f, centerY)
                    close()
                }
                if (fill != Color.Transparent) scope.drawPath(p, fill)
                scope.drawPath(p, edge, style = Stroke(dp(1.5.dp)))
            }
            EventKind.LSTM, EventKind.BOOT -> {
                val topLeft = Offset(px - size / 2f, centerY - size / 2f)
                val s = Size(size, size)
                if (fill != Color.Transparent) scope.drawRect(fill, topLeft, s)
                scope.drawRect(edge, topLeft, s, style = Stroke(dp(1.5.dp)))
            }
        }

        hits += MarkHit(
            Rect(px - dp(13.dp), centerY - boxH / 2f, px + dp(13.dp), centerY + boxH / 2f),
            e,
        )
    }

    fun drawRuler() {
        val step = rulerStepMs(vp.pxPerMs)
        val range = vp.visibleRange()
        var ts = range.first - floorMod(range.first, step)
        val top = dp(RULER_TOP)
        while (ts <= range.last) {
            val px = x(ts)
            val midnight = floorMod(ts, 86_400_000L) == 0L
            val text = if (midnight) dayLabel(ts) else hhmm(ts)
            centeredLabel(text, px, top, if (midnight) t.ink else t.muted, 10f)
            ts += step
        }
    }

    /**
     * "Ahora" is a line on the track, not the centre of the screen: the cursor
     * moves, the present does not. Scrolling it off-screen and finding it again is
     * how the past and the future stop being two views and become one.
     */
    fun drawNowLine() {
        val px = x(nowMs)
        if (px < -dp(40.dp) || px > w + dp(40.dp)) return
        scope.drawLine(
            t.accent.copy(alpha = 0.45f),
            Offset(px, 0f), Offset(px, scope.size.height - dp(26.dp)),
            strokeWidth = 1f,
        )
        val style = TextStyle(fontFamily = Mono, fontSize = 7.5.sp, letterSpacing = 0.12.em, color = Color.White)
        val laid = measurer.measure("AHORA", style)
        val padH = dp(6.dp)
        scope.drawRect(
            t.accent,
            Offset(px + dp(4.dp), 0f),
            Size(laid.size.width + padH * 2, dp(15.dp)),
        )
        scope.drawText(laid, topLeft = Offset(px + dp(4.dp) + padH, dp(2.dp)))
    }

    // --- text ---------------------------------------------------------------

    fun label(text: String, x: Float, y: Float, color: Color, size: Float) {
        scope.drawText(
            measurer, text, topLeft = Offset(x, y),
            style = TextStyle(fontFamily = Mono, fontSize = size.sp, letterSpacing = 0.08.em, color = color),
        )
    }

    /** Skips rather than clips: half a timestamp at the edge reads as a wrong one. */
    private fun centeredLabel(text: String, cx: Float, y: Float, color: Color, size: Float) {
        val style = TextStyle(fontFamily = Mono, fontSize = size.sp, letterSpacing = 0.04.em, color = color)
        val laid = measurer.measure(text, style)
        val left = cx - laid.size.width / 2f
        if (left < 0f || left + laid.size.width > w) return
        scope.drawText(laid, topLeft = Offset(left, y))
    }
}

// --- formatting --------------------------------------------------------------

internal fun floorMod(a: Long, b: Long): Long {
    val r = a % b
    return if (r < 0) r + b else r
}

internal fun hhmm(ms: Long): String {
    val ofDay = floorMod(ms, 86_400_000L)
    val h = (ofDay / 3_600_000L).toString().padStart(2, '0')
    val m = ((ofDay / 60_000L) % 60).toString().padStart(2, '0')
    return "$h:$m"
}

private fun dayLabel(ms: Long): String {
    val day = floorDivL(ms, 86_400_000L)
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

private fun floorDivL(a: Long, b: Long): Long {
    val q = a / b
    return if (a % b != 0L && (a xor b) < 0) q - 1 else q
}

internal fun humanGap(ms: Long): String {
    val min = ms / 60_000L
    return when {
        min >= 1440 -> "${min / 1440} d"
        min >= 60 -> {
            val h = min / 60
            val rem = min % 60
            if (rem == 0L) "$h h" else "$h h $rem min"
        }
        else -> "$min min"
    }
}
