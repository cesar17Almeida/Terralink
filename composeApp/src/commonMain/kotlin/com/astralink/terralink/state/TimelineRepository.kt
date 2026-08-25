package com.astralink.terralink.state

import com.astralink.terralink.db.TerralinkDb
import com.astralink.terralink.timeline.EventKind
import com.astralink.terralink.timeline.StationEvent
import com.astralink.terralink.timeline.eventKindFrom
import com.astralink.terralink.timeline.token

/**
 * The event journal the station can't keep for itself.
 *
 * A Savia node holds ~48 h of readings, 24 log lines and a couple of "last time
 * X" stamps -- all of it in RAM, all of it gone on the next power cycle. Every
 * connection harvests whatever of that is still there and appends it here, so the
 * app accumulates a real history instead of re-reading the same sliver.
 *
 * Process-singleton, initialised at app launch alongside [ReadingsRepository].
 */
object TimelineRepository {

    /** How far back the journal is kept. Past that the timeline is scrollable but
     *  empty, and the rows are dead weight in the database. */
    const val RETENTION_MS: Long = 30L * 24 * 3_600_000L

    private var db: TerralinkDb? = null

    fun init(db: TerralinkDb) {
        if (this.db == null) this.db = db
    }

    private fun queries() =
        (db ?: error("TimelineRepository not initialized. Call init(createTerralinkDb()) at app launch."))
            .timelineQueries

    /** Append events, ignoring the ones already journalled (same ts + kind + port). */
    fun record(stationId: String, events: List<StationEvent>) {
        if (events.isEmpty()) return
        val q = queries()
        q.transaction {
            for (e in events) {
                q.insertEvent(
                    station_id = stationId,
                    ts_ms = e.tsMs,
                    kind = e.kind.token(),
                    port = e.port.toLong(),
                    ok = if (e.ok) 1L else 0L,
                    detail = e.detail,
                )
            }
        }
    }

    /** Journalled events inside [fromMs, toMs), oldest first. */
    fun range(stationId: String, fromMs: Long, toMs: Long, limit: Long = 20_000): List<StationEvent> =
        queries().selectEventsByRange(stationId, fromMs, toMs, limit).executeAsList().mapNotNull { row ->
            val kind = eventKindFrom(row.kind) ?: return@mapNotNull null
            StationEvent(
                tsMs = row.ts_ms,
                kind = kind,
                port = row.port.toInt(),
                ok = row.ok == 1L,
                detail = row.detail,
            )
        }

    /** Newest journalled instant of one kind -- what the schedule anchors on. */
    fun lastOf(stationId: String, kind: EventKind): Long? =
        queries().maxEventTs(stationId, kind.token()).executeAsOne().MAX

    fun count(stationId: String): Long =
        queries().countEvents(stationId).executeAsOne()

    fun prune(stationId: String, nowMs: Long) {
        queries().pruneEventsBefore(stationId, nowMs - RETENTION_MS)
    }

    fun clear(stationId: String) {
        queries().deleteEventsByStation(stationId)
    }
}
