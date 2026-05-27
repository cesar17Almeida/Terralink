package com.astralink.terralink.state

import com.astralink.terralink.ble.protocol.Reading
import com.astralink.terralink.db.TerralinkDb

/**
 * Local cache of readings synced from a Pi. Backed by SQLDelight so the
 * data survives app restarts and queries (range, count, max ts) are O(log n).
 *
 * One process-singleton instance, initialised at app launch alongside the
 * StationsRepository. Insert batches go through a single transaction so a
 * 500-row sync flush stays sub-50 ms even on iOS.
 */
object ReadingsRepository {

    private var db: TerralinkDb? = null
    private var initialized = false

    fun init(db: TerralinkDb) {
        if (initialized) return
        this.db = db
        initialized = true
    }

    fun insertBatch(stationId: String, readings: List<Reading>) {
        val q = requireDb().readingsQueries
        q.transaction {
            for (r in readings) {
                // SQLDelight generates parameter names from the columns
                // (snake_case + `_` suffix on reserved words like value).
                q.insertReading(
                    station_id = stationId,
                    ts_ms = r.tsMs,
                    port = r.port.toLong(),
                    kind = r.kind,
                    value_ = r.value,
                    depth_cm = r.depthCm?.toLong(),
                )
            }
        }
    }

    fun countByStation(stationId: String): Long =
        requireDb().readingsQueries.countByStation(stationId).executeAsOne()

    /** Highest ts_ms cached for the station, or null if no rows yet. */
    fun maxTsByStation(stationId: String): Long? =
        requireDb().readingsQueries.maxTsByStation(stationId).executeAsOne().MAX

    fun selectByRange(
        stationId: String, fromMs: Long, toMs: Long, limit: Long = Long.MAX_VALUE,
    ): List<Reading> =
        // SQLDelight names: station_id, ts_ms (>=), ts_ms_ (<), value_ (LIMIT).
        requireDb().readingsQueries
            .selectByRange(
                station_id = stationId, ts_ms = fromMs, ts_ms_ = toMs, value_ = limit,
            )
            .executeAsList()
            .map { row ->
                Reading(
                    tsMs = row.ts_ms,
                    port = row.port.toInt(),
                    kind = row.kind,
                    value = row.value_,
                    depthCm = row.depth_cm?.toInt(),
                )
            }

    fun deleteByStation(stationId: String) {
        requireDb().readingsQueries.deleteByStation(stationId)
    }

    private fun requireDb(): TerralinkDb =
        db ?: error(
            "ReadingsRepository not initialized. Call init(createTerralinkDb()) at app launch."
        )
}
