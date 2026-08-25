// The timeline's viewport, its gesture handling and its haptics. Kept apart from
// the drawing so the track can be re-drawn two ways (one continuous pista, or one
// lane per event kind) over exactly the same scroll, zoom and feel.
package com.astralink.terralink.ui.components.timeline

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.runtime.withFrameMillis
import kotlin.math.abs

/** The three scales the design offers, as pixels per millisecond. Hour puts a
 *  quarter-hour on screen per centimetre; week fits a fortnight in a phone. */
enum class Zoom(val label: String, val pxPerMs: Float) {
    HOUR("H", 2.4f / 60_000f),
    DAY("D", 0.26f / 60_000f),
    WEEK("S", 0.04f / 60_000f),
}

/** How the same schedule is drawn: one continuous track, or one lane per kind. */
enum class TrackMode { PISTA, CARRILES }

private const val PX_PER_MS_MIN = 0.012f / 60_000f
private const val PX_PER_MS_MAX = 8f / 60_000f

/** A drag has to travel this far before it stops counting as a tap on a mark. */
internal const val TAP_SLOP_PX = 8f

/** Snap radius: a major event within this many pixels of the centre pulls it in. */
private const val SNAP_PX = 26f

/** Haptics are rate-limited to this, so a fast fling ticks instead of buzzing. */
private const val HAPTIC_MIN_GAP_MS = 28L

/**
 * Everything the track scrolls by. [centerMs] is the instant under the middle of
 * the viewport -- the cursor the user scrubs with -- and [pxPerMs] is the zoom.
 * Holding the viewport (rather than a scroll offset) keeps the geometry honest
 * across a zoom change: the instant you were looking at stays where it was.
 */
@Stable
class TimelineViewport internal constructor(centerMs: Long, pxPerMs: Float) {

    var centerMs by mutableStateOf(centerMs)
        internal set

    var pxPerMs by mutableStateOf(pxPerMs)
        internal set

    /** Viewport width in pixels, set by the track when it measures. */
    var widthPx by mutableStateOf(0f)
        internal set

    /** True while a finger is down or a fling is still running. */
    var scrubbing by mutableStateOf(false)
        internal set

    fun xOf(tsMs: Long): Float = (tsMs - centerMs) * pxPerMs + widthPx / 2f

    fun msAt(x: Float): Long = centerMs + ((x - widthPx / 2f) / pxPerMs).toLong()

    /** The instant at the left and right edges, with a little bleed so marks and
     *  curve segments entering the screen are already drawn when they arrive. */
    fun visibleRange(bleedPx: Float = 40f): LongRange =
        msAt(-bleedPx)..msAt(widthPx + bleedPx)

    fun setZoom(z: Zoom) { pxPerMs = z.pxPerMs }

    internal fun zoomBy(factor: Float) {
        pxPerMs = (pxPerMs * factor).coerceIn(PX_PER_MS_MIN, PX_PER_MS_MAX)
    }

    /** The preset this zoom currently reads as, or null when pinched off-preset. */
    val activeZoom: Zoom?
        get() = Zoom.entries.firstOrNull { abs(pxPerMs - it.pxPerMs) < it.pxPerMs * 0.08f }

    fun centerOn(tsMs: Long) { centerMs = tsMs }

    companion object {
        internal val Saver: Saver<TimelineViewport, List<Any>> = Saver(
            save = { listOf(it.centerMs, it.pxPerMs) },
            restore = { TimelineViewport(it[0] as Long, it[1] as Float) },
        )
    }
}

@androidx.compose.runtime.Composable
fun rememberTimelineViewport(nowMs: Long, zoom: Zoom = Zoom.DAY): TimelineViewport =
    rememberSaveable(saver = TimelineViewport.Saver) { TimelineViewport(nowMs, zoom.pxPerMs) }

/**
 * The haptic side of scrubbing.
 *
 * A timeline you drag with your thumb is a control with no detents, and without
 * feedback it reads as a picture that happens to move. Ticking as the cursor
 * crosses each event -- and landing with a distinctly heavier one when it snaps to
 * a major event -- turns it into something you can feel your way along, which is
 * the whole point of putting the station's schedule under a finger.
 *
 * The rate limit matters: a fling across a week crosses hundreds of samples, and
 * an unlimited tick per crossing is a continuous buzz, not information.
 */
internal class TimelineHaptics(private val haptics: HapticFeedback) {
    private var lastTickMs = 0L

    /** Cursor passed a sample: the fine, frequent detent. */
    fun minorTick(frameMs: Long) = fire(frameMs, HapticFeedbackType.SegmentFrequentTick)

    /** Cursor passed something that matters -- an uplink, a run of the model. */
    fun majorTick(frameMs: Long) = fire(frameMs, HapticFeedbackType.SegmentTick)

    /** The snap landed on an event. Not rate-limited: it ends a gesture. */
    fun landed() {
        haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
    }

    /** A mark was selected by tapping it. */
    fun picked() {
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
    }

    private fun fire(frameMs: Long, type: HapticFeedbackType) {
        if (frameMs - lastTickMs < HAPTIC_MIN_GAP_MS) return
        lastTickMs = frameMs
        haptics.performHapticFeedback(type)
    }
}

/**
 * Fling + snap, run as one coroutine so a new gesture can cancel it mid-flight.
 *
 * The decay is the design's: velocity shrinks by 6% a frame until it is slow
 * enough to stop mattering, then the nearest major event within [SNAP_PX] pulls
 * the cursor in over 200 ms. Snapping only to MAJOR events is deliberate -- at
 * hour zoom the samples are dense enough that snapping to every one of them would
 * make the track feel sticky rather than notched.
 *
 * [onMove] is handed every (before, after) pair so the caller can tick for each
 * event the cursor crossed during the fling, not just while a finger is down.
 */
internal suspend fun flingAndSnap(
    viewport: TimelineViewport,
    velocityPxPerMs: Float,
    majorTimes: List<Long>,
    haptics: TimelineHaptics,
    onMove: (before: Long, after: Long, frameMs: Long) -> Unit,
) {
    var v = velocityPxPerMs
    if (abs(v) > 0.03f) {
        var last = withFrameMillis { it }
        while (abs(v) > 0.02f) {
            val frameMs = withFrameMillis { it }
            val dt = (frameMs - last).coerceIn(1L, 32L)
            last = frameMs
            v *= 0.94f
            val before = viewport.centerMs
            viewport.centerMs = before - (v * dt / viewport.pxPerMs).toLong()
            onMove(before, viewport.centerMs, frameMs)
        }
    }

    val target = majorTimes.minByOrNull { abs((it - viewport.centerMs) * viewport.pxPerMs) }
    if (target != null && abs((target - viewport.centerMs) * viewport.pxPerMs) < SNAP_PX) {
        val from = viewport.centerMs
        val t0 = withFrameMillis { it }
        var k = 0f
        while (k < 1f) {
            val frameMs = withFrameMillis { it }
            k = ((frameMs - t0) / 200f).coerceAtMost(1f)
            val eased = 1f - (1f - k) * (1f - k) * (1f - k)
            viewport.centerMs = from + ((target - from) * eased).toLong()
        }
        haptics.landed()
    }
    viewport.scrubbing = false
}
