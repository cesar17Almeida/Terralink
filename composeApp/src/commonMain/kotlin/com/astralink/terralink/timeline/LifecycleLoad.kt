// One connection's worth of work: read what the station can still tell us, fold it
// into the local journal and archive, then hand back the merged past + projected
// future the screen draws.
package com.astralink.terralink.timeline

import com.astralink.terralink.ble.protocol.ConfigSnapshotMsg
import com.astralink.terralink.ble.protocol.Reading
import com.astralink.terralink.ble.protocol.StatusMsg
import com.astralink.terralink.ble.protocol.STATION_RAW_PAGE
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

/**
 * The spans one load covers, as one place instead of four scattered bounds.
 *
 * The upper bounds reach PAST the station's "now" deliberately, and that is the
 * whole point of extracting this: a point with a future timestamp is a real part
 * of the station's store (the app ingests the TA forecast that way, and the
 * dataset replay injects the measured HS30 of the very hours it is about to
 * predict). Cutting any of these at `now` leaves "Predicción vs real" holding
 * forecasts with nothing to score them against -- which is silent, because an
 * empty chart looks exactly like "no data yet".
 */
internal data class LoadWindow(
    val stationNowMs: Long,
    val fromMs: Long,          // oldest instant read out of the local stores
    val untilMs: Long,         // newest, ahead of now
    val fetchFromMs: Long,     // oldest instant asked of the station over BLE
    val fetchToMs: Long,       // newest, also ahead of now
)

internal fun loadWindow(stationNowMs: Long) = LoadWindow(
    stationNowMs = stationNowMs,
    fromMs = stationNowMs - PAST_MS,
    untilMs = stationNowMs + FUTURE_MS,
    fetchFromMs = stationNowMs - STATION_WINDOW_MS,
    fetchToMs = stationNowMs + FUTURE_MS,
)

/**
 * Every reading the station holds in [fromMs, toMs), pulled in pages.
 *
 * One request cannot do it: the firmware answers out of a 150-row buffer and
 * TRUNCATES SILENTLY past that -- no error, no "there is more" flag. Asking for
 * two days at once therefore returns the first 150 rows it happens to walk and
 * looks like a complete answer, which is exactly how the dataset replay came back
 * with 6 of its 24 measured hours.
 *
 * The cursor advances past the newest timestamp each page returned. The station
 * walks its ring in insertion order rather than by time, so a history whose
 * timestamps were written badly out of order could still hide a row behind the
 * cursor; the local cache dedups by primary key, so the next connection picks up
 * anything missed rather than double-counting it.
 */
private suspend fun fetchAllReadings(
    active: ActiveSession,
    fromMs: Long,
    toMs: Long,
    maxPages: Int = 8,          // 8 x 150 > the station's 600-row ring
): List<Reading> {
    val out = mutableListOf<Reading>()
    var cursor = fromMs
    repeat(maxPages) {
        val page = active.requestRawReadings(fromMs = cursor, toMs = toMs, limit = STATION_RAW_PAGE)
        if (page.isEmpty()) return out
        out += page
        if (page.size < STATION_RAW_PAGE) return out    // range exhausted
        val next = page.maxOf { it.tsMs } + 1
        if (next <= cursor) return out                  // no progress: stop rather than spin
        cursor = next
    }
    return out
}

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

    val w = loadWindow(stationNow)
    val fresh = runCatching { fetchAllReadings(active, w.fetchFromMs, w.fetchToMs) }
        .getOrDefault(emptyList())
    if (fresh.isNotEmpty()) ReadingsRepository.insertBatch(stationId, fresh)

    val predictions = runCatching { active.requestPredictions() }.getOrDefault(emptyList())
    ForecastArchive.archive(stationId, predictions)

    val logs = runCatching { active.requestLogs() }.getOrDefault(emptyList())

    TimelineRepository.record(
        stationId,
        harvestEvents(
            status = status,
            // Only readings that have actually happened become journal entries: a
            // future-stamped point is data the station holds, not a capture it made.
            readings = fresh.filter { it.tsMs <= stationNow },
            predictions = predictions,
            logs = logs,
        ),
    )
    TimelineRepository.prune(stationId, stationNow)
    ForecastArchive.prune(stationId, stationNow)

    // From here on everything comes out of the local stores, which hold more than
    // the station itself does: this is the point of journalling in the first place.
    val from = w.fromMs
    val until = w.untilMs
    val readings = ReadingsRepository.selectByRange(stationId, from, until)
    // The journal is the past by definition; the future half of the track comes
    // from projectSchedule, not from here.
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
