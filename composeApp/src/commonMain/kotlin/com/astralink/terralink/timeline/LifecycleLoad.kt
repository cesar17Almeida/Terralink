// One connection's worth of work: read what the station can still tell us, fold it
// into the local journal and archive, then hand back the merged past + projected
// future the screen draws.
package com.astralink.terralink.timeline

import com.astralink.terralink.ble.protocol.ConfigSnapshotMsg
import com.astralink.terralink.ble.protocol.Reading
import com.astralink.terralink.ble.protocol.StatusMsg
import com.astralink.terralink.ble.session.ActiveSession
import com.astralink.terralink.state.ArchivedForecast
import com.astralink.terralink.state.ForecastArchive
import com.astralink.terralink.state.ReadingsRepository
import com.astralink.terralink.state.TimelineRepository
import com.astralink.terralink.util.nowMs

/** How far either side of "ahora" the screen is populated. */
private const val PAST_MS = 7L * 24 * 3_600_000L
private const val FUTURE_MS = 2L * 24 * 3_600_000L

/** What the station still holds; asking for more is asking for nothing. */
private const val STATION_WINDOW_MS = 48L * 3_600_000L

/** Everything the lifecycle screen needs, already merged. */
data class LifecycleLoad(
    val config: ConfigSnapshotMsg,
    val status: StatusMsg,
    val stationNowMs: Long,
    val readings: List<Reading>,
    val events: List<StationEvent>,        // journal + projection, sorted
    val forecast: List<ArchivedForecast>,  // the newest run
    val archive: List<ArchivedForecast>,   // everything, for the error band
    val forecastRuns: Long,
)

/**
 * Read the station, journal what it said, and build the timeline.
 *
 * The reads are strictly sequential: the firmware serves one data_request at a
 * time over a shared notify stream, so overlapping them would interleave chunks
 * from two answers. Only the config and status reads are load-bearing -- a station
 * with no logs, no forecast or no readings still produces a timeline, just a
 * thinner one, so each of those is allowed to fail on its own.
 */
suspend fun loadLifecycle(active: ActiveSession, stationId: String): LifecycleLoad {
    val config = active.readConfig()
    val status = active.readStatus()
    // The station's clock, not the phone's: every timestamp it reports is in its
    // own frame, and an unsynced board would otherwise draw its life in 1970.
    val stationNow = status.nowMs ?: nowMs()

    val fresh = runCatching {
        active.requestRawReadings(fromMs = stationNow - STATION_WINDOW_MS, toMs = stationNow + 1)
    }.getOrDefault(emptyList())
    if (fresh.isNotEmpty()) ReadingsRepository.insertBatch(stationId, fresh)

    val predictions = runCatching { active.requestPredictions() }.getOrDefault(emptyList())
    ForecastArchive.archive(stationId, predictions)

    val logs = runCatching { active.requestLogs() }.getOrDefault(emptyList())

    TimelineRepository.record(
        stationId,
        harvestEvents(status = status, readings = fresh, predictions = predictions, logs = logs),
    )
    TimelineRepository.prune(stationId, stationNow)
    ForecastArchive.prune(stationId, stationNow)

    // From here on everything comes out of the local stores, which hold more than
    // the station itself does: this is the point of journalling in the first place.
    val from = stationNow - PAST_MS
    val until = stationNow + FUTURE_MS
    val readings = ReadingsRepository.selectByRange(stationId, from, stationNow + 1)
    val past = TimelineRepository.range(stationId, from, stationNow + 1)

    val anchors = ScheduleAnchors(
        nowMs = stationNow,
        lastSampleByPort = past.filter { it.kind == EventKind.SAMPLE }
            .groupBy { it.port }
            .mapValues { (_, v) -> v.maxOf { it.tsMs } },
        lastUplinkMs = TimelineRepository.lastOf(stationId, EventKind.LORA_UP),
        lastDailyMs = TimelineRepository.lastOf(stationId, EventKind.LSTM),
    )
    val future = projectSchedule(config, anchors, until)

    return LifecycleLoad(
        config = config,
        status = status,
        stationNowMs = stationNow,
        readings = readings,
        events = (past + future).sortedBy { it.tsMs },
        forecast = ForecastArchive.latest(stationId),
        archive = ForecastArchive.range(stationId, from, until),
        forecastRuns = ForecastArchive.runCount(stationId),
    )
}
