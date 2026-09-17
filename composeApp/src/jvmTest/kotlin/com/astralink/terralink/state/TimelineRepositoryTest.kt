package com.astralink.terralink.state

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.astralink.terralink.db.TerralinkDb
import com.astralink.terralink.timeline.EventKind
import com.astralink.terralink.timeline.StationEvent
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The journal is what the timeline draws. Each connection records the same
 * events again, reported at slightly different instants; the journal must keep
 * one mark per event and clean up the copies older builds left behind.
 */
class TimelineRepositoryTest {

    // The repository is a process singleton that keeps the first database it is
    // given, so every test shares this one and uses its own station id.
    companion object {
        val db: TerralinkDb = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).let { driver ->
            TerralinkDb.Schema.create(driver)
            TerralinkDb(driver)
        }.also { TimelineRepository.init(it) }
    }

    private val T = 1_787_659_200_000L

    private fun rows(station: String) = TimelineRepository.range(station, 0, Long.MAX_VALUE)

    @Test
    fun aRepeatedHarvestWithJitterAddsNothing() {
        val s = "rec"
        TimelineRepository.record(s, listOf(
            StationEvent(T, EventKind.BOOT, detail = "Arranque · fw savia_c"),
            StationEvent(T + 230, EventKind.LORA_UP, detail = "Uplink entregado · el gateway respondió"),
            StationEvent(T + 60_000, EventKind.SAMPLE, port = 1, detail = "Puerto 1 · 4 valores"),
        ))
        TimelineRepository.record(s, listOf(
            StationEvent(T + 700, EventKind.BOOT, detail = "Arranque · fw savia_c"),
            // The log has the cycle now: it keeps the first instant and takes the words.
            StationEvent(T, EventKind.LORA_UP, detail = "Uplink FORECAST · mínimo HS30 previsto · 4 B"),
            StationEvent(T + 60_000, EventKind.SAMPLE, port = 1, detail = "Puerto 1 · 4 valores"),
            // A capture a minute later is its own mark.
            StationEvent(T + 120_000, EventKind.SAMPLE, port = 1, detail = "Puerto 1 · 4 valores"),
        ))
        val all = rows(s)
        assertEquals(1, all.count { it.kind == EventKind.BOOT })
        assertEquals(2, all.count { it.kind == EventKind.SAMPLE })
        val up = all.single { it.kind == EventKind.LORA_UP }
        assertEquals(T + 230, up.tsMs)
        assertEquals("Uplink FORECAST · mínimo HS30 previsto · 4 B", up.detail)
    }

    @Test
    fun cyclesAPeriodApartAreKept() {
        val s = "period"
        TimelineRepository.record(s, (0 until 3).map {
            StationEvent(T + it * 300_000L, EventKind.LORA_UP, detail = "Uplink FORECAST")
        })
        assertEquals(3, rows(s).size)
    }

    @Test
    fun pruneCollapsesCopiesLeftByOlderBuilds() {
        val s = "legacy"
        val q = db.timelineQueries
        // What the exact-key journal accumulated: a mark per source and per read.
        q.insertEvent(s, T, "lora_up", -1, 1, "Uplink entregado · el gateway respondió")
        q.insertEvent(s, T + 9_000, "lora_up", -1, 1, "Uplink FORECAST · mínimo HS30 previsto · 4 B")
        q.insertEvent(s, T + 16_000, "lora_up", -1, 1, "trama BOOT")
        q.insertEvent(s, T + 16_000, "lora_down", -1, 0, "Downlink con una hora inverosímil · ignorado")
        q.insertEvent(s, T + 16_450, "lora_down", -1, 1, "Recepción del gateway")
        q.insertEvent(s, T - 3_000_000, "boot", -1, 1, "Arranque · fw savia_c")
        q.insertEvent(s, T - 2_999_300, "boot", -1, 1, "Arranque · fw savia_c")
        q.insertEvent(s, T, "sample", 1, 1, "Puerto 1 · 4 valores")
        q.insertEvent(s, T + 1, "sample", 1, 1, "Puerto 1 · 4 valores")
        // Another cycle an hour later stays.
        q.insertEvent(s, T + 3_600_000, "lora_up", -1, 1, "Uplink FORECAST")

        TimelineRepository.prune(s, T + 3_600_000)

        val all = rows(s)
        val up = all.filter { it.kind == EventKind.LORA_UP }
        assertEquals(listOf(T, T + 3_600_000), up.map { it.tsMs })
        assertEquals("Uplink FORECAST · mínimo HS30 previsto · 4 B", up.first().detail)
        val down = all.single { it.kind == EventKind.LORA_DOWN }
        assertEquals(T + 16_000, down.tsMs)
        assertEquals(false, down.ok)
        assertEquals(1, all.count { it.kind == EventKind.BOOT })
        assertEquals(2, all.count { it.kind == EventKind.SAMPLE })   // exact stamps: untouched
        // Nothing left to collapse the second time.
        TimelineRepository.prune(s, T + 3_600_000)
        assertEquals(all, rows(s))
    }
}
