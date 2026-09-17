package com.astralink.terralink.state

import com.astralink.terralink.db.TerralinkDb
import com.astralink.terralink.timeline.EventKind
import com.astralink.terralink.timeline.StationEvent
import com.astralink.terralink.timeline.clusterWithin
import com.astralink.terralink.timeline.eventKindFrom
import com.astralink.terralink.timeline.sameEventWindowMs
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

    /**
     * Append events, skipping the ones already journalled: same kind and port
     * within [sameEventWindowMs]. A later report with longer words replaces the
     * journalled ones; the instant stays the first one seen.
     */
    fun record(stationId: String, events: List<StationEvent>) {
        if (events.isEmpty()) return
        val q = queries()
        q.transaction {
            for (e in events) {
                val kind = e.kind.token()
                val port = e.port.toLong()
                val ok = if (e.ok) 1L else 0L
                val window = e.kind.sameEventWindowMs()
                val near = q.selectNearestEvent(
                    station_id = stationId, kind = kind, port = port,
                    from_ms = e.tsMs - window, to_ms = e.tsMs + window, center_ms = e.tsMs,
                ).executeAsOneOrNull()
                if (near == null) {
                    q.insertEvent(stationId, e.tsMs, kind, port, ok, e.detail)
                } else if (e.detail.length > near.detail.length) {
                    q.updateEventWords(
                        detail = e.detail, ok = minOf(ok, near.ok),
                        station_id = stationId, ts_ms = near.ts_ms, kind = kind, port = port,
                    )
                }
            }
        }
    }

    /** Collapse marks that one event left several times in the journal (builds
     *  before [record] matched by window wrote one per source). */
    fun compact(stationId: String) {
        val q = queries()
        q.transaction {
            q.selectLandmarkEvents(stationId).executeAsList()
                .groupBy { it.kind to it.port }
                .forEach { (key, rows) ->
                    val window = eventKindFrom(key.first)?.sameEventWindowMs() ?: return@forEach
                    for (run in clusterWithin(rows, window) { it.ts_ms }) {
                        if (run.size < 2) continue
                        val keep = run.first()
                        run.drop(1).forEach { q.deleteEvent(stationId, it.ts_ms, it.kind, it.port) }
                        q.updateEventWords(
                            detail = run.maxBy { it.detail.length }.detail,
                            ok = run.minOf { it.ok },
                            station_id = stationId, ts_ms = keep.ts_ms, kind = keep.kind, port = keep.port,
                        )
                    }
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
        compact(stationId)
    }

    fun clear(stationId: String) {
        queries().deleteEventsByStation(stationId)
    }
}
