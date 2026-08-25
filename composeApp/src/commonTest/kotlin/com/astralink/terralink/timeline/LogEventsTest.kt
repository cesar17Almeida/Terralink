package com.astralink.terralink.timeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The station's 24-line log ring is the only place it says an uplink left or the
 * model ran. Parsing it is what lets the timeline show those without a firmware
 * change -- and the stamp has no date, so resolving it correctly across midnight is
 * the part that can quietly put an event on the wrong day.
 */
class LogEventsTest {

    // 2026-08-25T12:00:00Z.
    private val NOW = 1_787_659_200_000L
    private val DAY = 86_400_000L

    @Test
    fun aStampedLineLandsOnTodayWhenItIsBeforeNow() {
        val events = parseLogEvents(listOf("11:30:00 LoRa: uplink sent, no downlink\n"), NOW)
        assertEquals(1, events.size)
        assertEquals(NOW - 30 * 60_000L, events.first().tsMs)
        assertEquals(EventKind.LORA_UP, events.first().kind)
    }

    @Test
    fun aStampLaterThanNowBelongsToYesterday() {
        // 23:45 read at 12:00 can only be last night: the ring never holds the future.
        val events = parseLogEvents(listOf("23:45:00 inference: HS30 24h forecast stored (min=0.180)"), NOW)
        val expected = NOW - 12 * 3_600_000L + 23 * 3_600_000L + 45 * 60_000L - DAY
        assertEquals(expected, events.first().tsMs)
    }

    @Test
    fun unstampedLinesAreDroppedRatherThanGuessedAt() {
        // "+42s" means the clock was not synced when the line was written. An event
        // we cannot place in time is worse on a timeline than no event.
        val events = parseLogEvents(listOf("+42s LoRa: uplink sent, no downlink"), NOW)
        assertTrue(events.isEmpty())
    }

    @Test
    fun failuresAreMarkedAsFailures() {
        val events = parseLogEvents(
            listOf(
                "10:00:00 LoRa: uplink timeout",
                "10:05:00 LoRa: uplink sent, no downlink",
            ),
            NOW,
        )
        assertFalse(events[0].ok)
        assertTrue(events[1].ok)
    }

    @Test
    fun downlinksAreTheirOwnKind() {
        val events = parseLogEvents(listOf("09:00:00 LoRa downlink: 6 past + 24 future TA"), NOW)
        assertEquals(EventKind.LORA_DOWN, events.first().kind)
    }

    @Test
    fun aSkippedInferenceExplainsItself() {
        val events = parseLogEvents(
            listOf("20:00:00 inference: skipped -- not enough history (status=3)"), NOW,
        )
        assertEquals(EventKind.LSTM, events.first().kind)
        assertFalse(events.first().ok)
        assertTrue(events.first().detail.contains("not enough history"))
    }

    @Test
    fun linesWithNothingToSayAreIgnored() {
        val events = parseLogEvents(listOf("10:00:00 ble: client connected"), NOW)
        assertTrue(events.isEmpty())
    }
}
