package com.astralink.terralink.ble.session

import com.astralink.terralink.ble.protocol.Reading
import com.astralink.terralink.ble.protocol.STATION_RAW_PAGE
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runs a suspend block whose fakes never suspend. */
private fun <T> runSync(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
    return result!!.getOrThrow()
}

/**
 * The station answers raw queries from a 150-row buffer, oldest first, and truncates
 * silently. A sync that asked for 500 and stopped on the first short page kept 150 rows
 * and moved its cursor past the rest; these pin the pager against that station.
 */
class RawPagingTest {

    private val t0 = 1_780_000_000_000L
    private val halfHour = 1_800_000L

    /** 48 h of an AquaCheck: 4 depths stored at the same instant every 30 min. */
    private val ring: List<Reading> = (0 until 96).flatMap { i ->
        (1..4).map { d ->
            Reading(tsMs = t0 + i * halfHour, port = 1, kind = "soil_moisture", value = 0.3, depthCm = 10 * d)
        }
    }

    private val requestedLimits = mutableListOf<Int>()

    /** Firmware query: rows in [from, to] in ring order, cut at 150 whatever the limit. */
    private fun station(rows: List<Reading>): suspend (Long, Long, Int) -> List<Reading> = { from, to, limit ->
        requestedLimits += limit
        rows.filter { it.tsMs in from..to }.take(minOf(limit, STATION_RAW_PAGE))
    }

    @Test
    fun pagesThroughEveryRowOfA48hAquaCheckRing() {
        val got = runSync { pageRawReadings(t0, t0 + 48 * 2 * halfHour, fetch = station(ring)) }
        assertEquals(384, ring.size)
        assertEquals(ring.toSet(), got.toSet())
        assertEquals(got.size, got.toSet().size, "no row twice")
        assertTrue(requestedLimits.all { it == STATION_RAW_PAGE })
    }

    @Test
    fun aPageCutInsideATimestampRefetchesThatInstant() {
        // 150 = 37 full instants + 2 rows of the 38th.
        val step = rawPageStep(ring.take(STATION_RAW_PAGE), t0)
        assertEquals(148, step.keep.size)
        assertEquals(t0 + 37 * halfHour, step.next)
    }

    @Test
    fun aShortPageIsTheEnd() {
        val step = rawPageStep(ring.take(10), t0)
        assertEquals(10, step.keep.size)
        assertNull(step.next)
        assertTrue(runSync { pageRawReadings(t0, t0 + 1, fetch = station(emptyList())) }.isEmpty())
    }

    @Test
    fun aFullPageOnOneInstantStillMovesOn() {
        val same = List(STATION_RAW_PAGE) { Reading(tsMs = t0, port = 1, kind = "generic", value = it.toDouble()) }
        val step = rawPageStep(same, t0)
        assertEquals(STATION_RAW_PAGE, step.keep.size)
        assertEquals(t0 + 1, step.next)
    }
}
