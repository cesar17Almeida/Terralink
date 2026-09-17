package com.astralink.terralink.ble.session

import com.astralink.terralink.ble.protocol.Reading
import com.astralink.terralink.ble.protocol.STATION_RAW_PAGE

/** Rows of one page that are safe to keep, and where the next page starts (null = done). */
class RawPageStep(val keep: List<Reading>, val next: Long?)

/**
 * Advance through the station's raw readings one page at a time.
 *
 * The station answers at most [STATION_RAW_PAGE] rows, oldest first, and cuts silently,
 * so only a short page means "drained". A full page may end in the middle of a
 * timestamp (an AquaCheck stores its four depths at the same instant), so the rows of
 * the newest timestamp are dropped and asked for again from that instant.
 */
fun rawPageStep(page: List<Reading>, cursor: Long, pageSize: Int = STATION_RAW_PAGE): RawPageStep {
    if (page.size < pageSize) return RawPageStep(page, null)
    val newest = page.maxOf { it.tsMs }
    val older = page.filter { it.tsMs < newest }
    return if (older.isNotEmpty()) RawPageStep(older, newest)
    else RawPageStep(page, maxOf(newest + 1, cursor + 1))   // a whole page on one instant
}

/** Every reading in [fromMs, toMs] through [fetch] (from, to, limit), one station page at a time. */
suspend fun pageRawReadings(
    fromMs: Long,
    toMs: Long,
    maxPages: Int = 8,          // 8 x 150 > the station's 600-row ring
    fetch: suspend (from: Long, to: Long, limit: Int) -> List<Reading>,
): List<Reading> {
    val out = mutableListOf<Reading>()
    var cursor = fromMs
    repeat(maxPages) {
        val step = rawPageStep(fetch(cursor, toMs, STATION_RAW_PAGE), cursor)
        out += step.keep
        cursor = step.next ?: return out
    }
    return out
}

/** Every raw reading the station holds in [fromMs, toMs]; a single request would truncate at 150. */
suspend fun ActiveSession.requestAllRawReadings(fromMs: Long, toMs: Long, maxPages: Int = 8): List<Reading> =
    pageRawReadings(fromMs, toMs, maxPages) { from, to, limit ->
        requestRawReadings(fromMs = from, toMs = to, limit = limit)
    }
