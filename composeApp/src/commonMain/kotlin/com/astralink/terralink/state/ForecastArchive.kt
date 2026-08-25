package com.astralink.terralink.state

import com.astralink.terralink.ble.protocol.Prediction
import com.astralink.terralink.db.TerralinkDb

/** One archived forecast point: what the model said, when it said it. */
data class ArchivedForecast(
    val issuedMs: Long,
    val targetMs: Long,
    val model: String,
    val value: Double,
    val horizonH: Int,
)

/**
 * A copy of every forecast the app has ever seen, kept so predictions can be
 * scored against what actually happened.
 *
 * The station publishes only its NEWEST forecast: read it tomorrow and yesterday's
 * is gone, overwritten by the run that replaced it. By then the hours it predicted
 * have been measured -- which is exactly when the comparison becomes possible and
 * exactly when the prediction has stopped existing. So the app archives each
 * forecast at the moment it reads it, and "Predicción vs real" joins the archive
 * against the readings cache afterwards.
 */
object ForecastArchive {

    const val KIND_HS30 = "hs30_forecast"

    /** Forecasts are scored against readings, which the station only keeps ~48 h
     *  of; a month of archive outlives anything we can still compare it to. */
    const val RETENTION_MS: Long = 60L * 24 * 3_600_000L

    private const val HOUR_MS = 3_600_000L

    private var db: TerralinkDb? = null

    fun init(db: TerralinkDb) {
        if (this.db == null) this.db = db
    }

    private fun queries() =
        (db ?: error("ForecastArchive not initialized. Call init(createTerralinkDb()) at app launch."))
            .timelineQueries

    /**
     * Archive one forecast run. `issued_ms` is not on the wire -- the station never
     * says when it inferred -- so it is derived from the run's own first step: the
     * horizon starts at H+1 (see PredictionsScreen), so the inference happened one
     * hour before the earliest target. That keeps two runs of the same hours apart
     * without inventing a timestamp the station never produced, and it stays stable
     * when the same forecast is read twice (a phone-clock `seen` stamp would not,
     * and would archive a duplicate on every refresh).
     *
     * Returns the run's issued instant, or null when there was nothing to archive.
     */
    fun archive(stationId: String, predictions: List<Prediction>, kind: String = KIND_HS30): Long? {
        val run = predictions.filter { it.kind == kind }.sortedBy { it.tsMs }
        if (run.isEmpty()) return null
        val issued = run.first().tsMs - HOUR_MS
        val q = queries()
        q.transaction {
            run.forEachIndexed { i, p ->
                q.insertForecast(
                    station_id = stationId,
                    issued_ms = issued,
                    target_ms = p.tsMs,
                    model = p.model,
                    kind = p.kind,
                    value_ = p.value,
                    horizon_h = (i + 1).toLong(),
                )
            }
        }
        return issued
    }

    /** Every archived point whose target hour falls in [fromMs, toMs). */
    fun range(stationId: String, fromMs: Long, toMs: Long, kind: String = KIND_HS30): List<ArchivedForecast> =
        queries().selectForecastByRange(stationId, kind, fromMs, toMs).executeAsList().map(::toModel)

    /** The newest run the station published, all its horizon steps. */
    fun latest(stationId: String, kind: String = KIND_HS30): List<ArchivedForecast> =
        queries().selectLatestForecast(stationId, kind, stationId, kind).executeAsList().map(::toModel)

    /** How many distinct runs are archived -- what the accuracy screen has to work with. */
    fun runCount(stationId: String, kind: String = KIND_HS30): Long =
        queries().countForecastRuns(stationId, kind).executeAsOne()

    fun prune(stationId: String, nowMs: Long) {
        queries().pruneForecastBefore(stationId, nowMs - RETENTION_MS)
    }

    fun clear(stationId: String) {
        queries().deleteForecastByStation(stationId)
    }

    private fun toModel(row: com.astralink.terralink.db.Forecast) = ArchivedForecast(
        issuedMs = row.issued_ms,
        targetMs = row.target_ms,
        model = row.model,
        value = row.value_,
        horizonH = row.horizon_h.toInt(),
    )
}
