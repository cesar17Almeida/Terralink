// The track itself: one canvas that draws the station's past and future on a
// single scrollable time axis, and one gesture handler that lets a thumb scrub it.
package com.astralink.terralink.ui.components.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.astralink.terralink.timeline.EventKind
import com.astralink.terralink.timeline.StationEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Fixed track height: the design's 326 px of stacked bands. */
val TRACK_HEIGHT = 326.dp

/** A mark's hit box, kept from the last draw so a tap can find what it landed on. */
internal class MarkHit(val rect: Rect, val event: StationEvent)

@Composable
fun TimelineTrack(
    data: TrackData,
    viewport: TimelineViewport,
    nowMs: Long,
    mode: TrackMode,
    selected: StationEvent?,
    onSelect: (StationEvent?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = timeTones()
    val measurer = rememberTextMeasurer()
    val hapticFeedback = LocalHapticFeedback.current
    val haptics = remember(hapticFeedback) { TimelineHaptics(hapticFeedback) }
    val scope = rememberCoroutineScope()
    val hits = remember { mutableListOf<MarkHit>() }
    val majors = remember(data.events) { data.events.majorTimes() }
    // Sorted once per data change: crossing detection runs on every frame of a
    // fling, so it binary-searches instead of walking thousands of events.
    val majorArray = remember(majors) { majors.toLongArray() }
    val minorArray = remember(data.events) {
        data.events.filter { !it.kind.isMajor() }.map { it.tsMs }.distinct().sorted().toLongArray()
    }
    val fling = remember { FlingHolder() }

    // The Y domain is taken from the WHOLE series, not the visible slice, so the
    // curve keeps its shape while you scroll instead of rescaling under your thumb.
    val domain = remember(data.series) { valueDomain(data.series) }

    // Ticking as the cursor crosses events is what makes this feel like a control
    // and not a picture. Both the drag and the fling route through here.
    fun tickCrossings(before: Long, after: Long, frameMs: Long) {
        if (before == after) return
        val lo = min(before, after)
        val hi = max(before, after)
        if (majorArray.spans(lo, hi)) haptics.majorTick(frameMs)
        else if (minorArray.spans(lo, hi)) haptics.minorTick(frameMs)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(TRACK_HEIGHT)
            .onSizeChanged { viewport.widthPx = it.width.toFloat() }
            .pointerInput(data.events, viewport) {
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    fling.job?.cancel()
                    viewport.scrubbing = true
                    var travel = 0f
                    var velocity = 0f
                    var lastMs = 0L
                    var previous: List<PointerInputChange> = listOf(first)

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break

                        val pan = event.panBy(previous)
                        val zoom = event.zoomBy(previous)
                        previous = pressed

                        if (zoom != 1f) viewport.zoomBy(zoom)
                        if (pan != 0f) {
                            travel += abs(pan)
                            val before = viewport.centerMs
                            viewport.centerMs = before - (pan / viewport.pxPerMs).toLong()
                            val stamp = event.changes.first().uptimeMillis
                            val dt = (stamp - lastMs).coerceIn(1L, 40L)
                            velocity = if (lastMs == 0L) 0f else pan / dt
                            lastMs = stamp
                            tickCrossings(before, viewport.centerMs, stamp)
                        }
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }

                    if (travel < TAP_SLOP_PX) {
                        // A tap, not a drag: pick the mark under the finger.
                        val hit = hits.firstOrNull { it.rect.contains(first.position) }
                        viewport.scrubbing = false
                        if (hit != null) {
                            haptics.picked()
                            onSelect(if (selected sameAs hit.event) null else hit.event)
                        } else {
                            onSelect(null)
                        }
                    } else {
                        fling.job = scope.launch {
                            flingAndSnap(viewport, velocity, majors, haptics, ::tickCrossings)
                        }
                    }
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(TRACK_HEIGHT)) {
            hits.clear()
            val ctx = TrackDraw(this, viewport, t, measurer, nowMs, domain, hits, selected)
            when (mode) {
                TrackMode.PISTA -> ctx.drawPista(data)
                TrackMode.CARRILES -> ctx.drawCarriles(data)
            }
            ctx.drawRuler()
            ctx.drawNowLine()
        }
    }
}

/** Holds the fling coroutine so a new touch can cancel the one still running. */
private class FlingHolder { var job: Job? = null }

/**
 * True when any instant in this sorted array falls inside [lo, hi]. Hand-rolled
 * because the stdlib's primitive-array binarySearch is JVM-only, and this runs on
 * every frame of a fling on all three targets.
 */
private fun LongArray.spans(lo: Long, hi: Long): Boolean {
    if (isEmpty()) return false
    var low = 0
    var high = size            // first index whose value is >= lo
    while (low < high) {
        val mid = (low + high) ushr 1
        if (this[mid] < lo) low = mid + 1 else high = mid
    }
    return low < size && this[low] <= hi
}

/** Two events are the same mark when they are the same instant, kind and port. */
internal infix fun StationEvent?.sameAs(other: StationEvent): Boolean =
    this != null && tsMs == other.tsMs && kind == other.kind && port == other.port

// --- gesture maths -----------------------------------------------------------
// Computed here rather than pulled from the foundation helpers, which are not part
// of its public surface.

private fun PointerEvent.panBy(previous: List<PointerInputChange>): Float {
    val now = changes.filter { it.pressed }
    if (now.isEmpty()) return 0f
    var sum = 0f
    var n = 0
    for (c in now) {
        val was = previous.firstOrNull { it.id == c.id } ?: continue
        sum += c.position.x - was.position.x
        n++
    }
    return if (n == 0) 0f else sum / n
}

private fun PointerEvent.zoomBy(previous: List<PointerInputChange>): Float {
    val now = changes.filter { it.pressed }
    if (now.size < 2) return 1f
    val wasPair = now.mapNotNull { c -> previous.firstOrNull { it.id == c.id } }
    if (wasPair.size < 2) return 1f
    val before = spread(wasPair.map { it.position })
    val after = spread(now.map { it.position })
    return if (before < 1f || after < 1f) 1f else after / before
}

private fun spread(points: List<Offset>): Float {
    val cx = points.sumOf { it.x.toDouble() }.toFloat() / points.size
    val cy = points.sumOf { it.y.toDouble() }.toFloat() / points.size
    var sum = 0f
    for (p in points) sum += sqrt((p.x - cx) * (p.x - cx) + (p.y - cy) * (p.y - cy))
    return sum / points.size
}

