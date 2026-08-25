// What the track draws: the marks, the value curve, the sleep rail and the ruler.
// Pure geometry over the events and series a screen hands it -- no BLE, no state.
package com.astralink.terralink.ui.components.timeline

import com.astralink.terralink.timeline.EventKind
import com.astralink.terralink.timeline.StationEvent

/** A point of the measured or forecast series, in the sensor's own units. */
data class TrackPoint(val tsMs: Long, val value: Double)

/**
 * One value series drawn behind the marks. [band] is an optional uncertainty
 * envelope; it is only ever non-null when the app can compute it from something
 * real (see the accuracy archive), never as decoration.
 */
data class TrackSeries(
    val measured: List<TrackPoint> = emptyList(),
    val forecast: List<TrackPoint> = emptyList(),
    val band: List<Triple<Long, Double, Double>> = emptyList(),  // ts, low, high
    val unit: String = "",
    val reference: Double? = null,        // a horizontal line worth naming
    val referenceLabel: String = "",
)

/** The events the track shows, already merged past + future and sorted. */
data class TrackData(
    val events: List<StationEvent>,
    val series: TrackSeries = TrackSeries(),
)

/** Sample marks are the fine grain; everything else is a landmark you can snap
 *  to and read a story from. */
fun EventKind.isMajor(): Boolean = this != EventKind.SAMPLE

/** Instants worth snapping to, deduplicated. */
fun List<StationEvent>.majorTimes(): List<Long> =
    filter { it.kind.isMajor() }.map { it.tsMs }.distinct().sorted()

/** Every instant the station was awake -- the complement of the sleep rail. */
fun List<StationEvent>.wakeTimes(): List<Long> = map { it.tsMs }.distinct().sorted()

/**
 * The gaps between wakes, as (start, end) pairs longer than [minGapMs].
 *
 * This is the sleep rail, and it is the honest way to draw deep sleep: the app
 * cannot observe the station sleeping, only that nothing happened between two
 * things that did. A gap is therefore evidence of sleep, not a claim about it,
 * which is why short gaps are dropped -- below a few minutes they are just the
 * spacing between two parts of one wake.
 */
fun List<StationEvent>.sleepGaps(minGapMs: Long = 5 * 60_000L): List<LongRange> {
    val times = wakeTimes()
    if (times.size < 2) return emptyList()
    val out = mutableListOf<LongRange>()
    for (i in 0 until times.size - 1) {
        val gap = times[i + 1] - times[i]
        if (gap >= minGapMs) out += times[i]..times[i + 1]
    }
    return out
}

/** How many wakes and how long asleep over the trailing 24 h; the footer's numbers. */
data class DayStats(val wakes: Int, val asleepFraction: Double, val loraOk: Int, val loraTotal: Int)

fun List<StationEvent>.dayStats(nowMs: Long): DayStats {
    val day = filter { !it.future && it.tsMs > nowMs - 86_400_000L && it.tsMs <= nowMs }
    val wakeInstants = day.map { it.tsMs }.distinct().sorted()
    // Awake time is unobservable to the millisecond, so it is taken as the span the
    // wakes cover minus the gaps that clearly are sleep: an under-estimate of sleep
    // that never claims more rest than the evidence supports.
    val asleep = day.sleepGaps().sumOf { it.last - it.first }
    val lora = day.filter { it.kind == EventKind.LORA_UP }
    return DayStats(
        wakes = wakeInstants.size,
        asleepFraction = (asleep.toDouble() / 86_400_000.0).coerceIn(0.0, 1.0),
        loraOk = lora.count { it.ok },
        loraTotal = lora.size,
    )
}

/** Ruler step (ms) that keeps labels roughly 90 px apart at the current zoom. */
fun rulerStepMs(pxPerMs: Float): Long {
    val target = 90f / pxPerMs                       // ms per label at this zoom
    val steps = longArrayOf(
        60_000L, 300_000L, 900_000L, 1_800_000L, 3_600_000L, 10_800_000L,
        21_600_000L, 43_200_000L, 86_400_000L, 172_800_000L, 604_800_000L,
    )
    return steps.firstOrNull { it >= target } ?: steps.last()
}

/** Sample marks become a density band once they are closer together than this. */
const val DOT_VISIBLE_PX_PER_MS: Float = 0.85f / 60_000f
