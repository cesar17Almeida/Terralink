// Assembling what the lifecycle screen draws, out of the local stores. Pure
// functions over already-fetched data, so the screen file stays orchestration.
package com.astralink.terralink.timeline

import com.astralink.terralink.ble.protocol.ConfigSnapshotMsg
import com.astralink.terralink.ble.protocol.Reading
import com.astralink.terralink.ble.protocol.ReadingKind
import com.astralink.terralink.state.ArchivedForecast
import com.astralink.terralink.ui.components.timeline.SeriesChip
import com.astralink.terralink.ui.components.timeline.TrackPoint
import com.astralink.terralink.ui.components.timeline.TrackSeries
import kotlin.math.abs

/** The depth the LSTM forecasts. Only this series gets a predicted continuation. */
const val LSTM_DEPTH_CM = 30

/** A drawable channel: one sensor port's one kind at one depth. */
data class SeriesKey(val port: Int, val kind: String, val depthCm: Int?) {
    val id: String get() = "$port:$kind:${depthCm ?: -1}"

    /** True for the channel the LSTM predicts -- soil moisture at 30 cm. */
    val isForecastable: Boolean
        get() = kind == ReadingKind.SOIL_MOISTURE && depthCm == LSTM_DEPTH_CM
}

/** The channels present in [readings], newest-activity first so the chips open on
 *  something that actually has data. */
fun seriesKeysOf(readings: List<Reading>): List<SeriesKey> =
    readings
        .groupBy { SeriesKey(it.port, it.kind, it.depthCm) }
        .entries
        .sortedWith(compareByDescending<Map.Entry<SeriesKey, List<Reading>>> {
            it.key.isForecastable
        }.thenByDescending { e -> e.value.maxOf { it.tsMs } })
        .map { it.key }

fun SeriesKey.chip(): SeriesChip = SeriesChip(
    id = id,
    label = when (kind) {
        ReadingKind.SOIL_MOISTURE -> depthCm?.let { "Humedad $it cm" } ?: "Humedad suelo"
        ReadingKind.SOIL_TEMPERATURE -> depthCm?.let { "T. suelo $it cm" } ?: "T. suelo"
        ReadingKind.AIR_TEMPERATURE -> "T. aire"
        ReadingKind.AIR_HUMIDITY -> "H. aire"
        "distance" -> "Distancia"
        else -> "P$port · $kind"
    },
    unit = unitOf(kind),
)

fun unitOf(kind: String): String = when (kind) {
    ReadingKind.SOIL_MOISTURE -> "VWC 0–1"
    ReadingKind.SOIL_TEMPERATURE, ReadingKind.AIR_TEMPERATURE -> "°C"
    ReadingKind.AIR_HUMIDITY -> "%RH"
    "distance" -> "mm"
    else -> ""
}

/**
 * The curve behind the marks: what was measured, and -- for the one channel the
 * model predicts -- what it says comes next.
 *
 * The uncertainty band is NOT a decoration and is not invented. It is the model's
 * own historical error at that horizon, measured by this app against readings it
 * already holds, and it appears only once there are enough scored hours to mean
 * anything. A forecast with no track record is drawn as a bare dashed line, which
 * is the honest picture of what is known about it.
 */
fun buildSeries(
    key: SeriesKey,
    readings: List<Reading>,
    forecast: List<ArchivedForecast>,
    archive: List<ArchivedForecast>,
    nowMs: Long,
): TrackSeries {
    val measured = readings
        .filter { it.port == key.port && it.kind == key.kind && it.depthCm == key.depthCm }
        .sortedBy { it.tsMs }
        .map { TrackPoint(it.tsMs, it.value) }

    if (!key.isForecastable || forecast.isEmpty()) {
        return TrackSeries(measured = measured, unit = unitOf(key.kind))
    }

    val ahead = forecast.filter { it.targetMs >= nowMs }.sortedBy { it.targetMs }
    // Join the forecast onto the last measurement so the track reads as one line
    // crossing "ahora" rather than two lines that happen to share an axis.
    val head = measured.lastOrNull()?.takeIf { it.tsMs <= (ahead.firstOrNull()?.targetMs ?: 0L) }
    val fcPoints = listOfNotNull(head) + ahead.map { TrackPoint(it.targetMs, it.value) }

    val errors = horizonErrors(archive, readings, key)
    val band = if (errors.isEmpty()) emptyList() else buildList {
        // The band opens from the last measurement, where the error is zero by
        // definition, instead of starting as a rectangle an hour into the future.
        head?.let { add(Triple(it.tsMs, it.value, it.value)) }
        ahead.forEach { f ->
            errors[f.horizonH]?.let { e -> add(Triple(f.targetMs, f.value - e, f.value + e)) }
        }
    }

    val min = ahead.minByOrNull { it.value }?.value
    return TrackSeries(
        measured = measured,
        forecast = if (fcPoints.size > 1) fcPoints else emptyList(),
        band = band,
        unit = unitOf(key.kind),
        reference = min,
        referenceLabel = min?.let { "Mínimo previsto ${fmtDecimals(it, 3)}" } ?: "",
    )
}

/** Scored hours needed overall before an error band means anything. */
private const val MIN_PAIRS = 8

/** ...and per horizon, before that horizon's own number is trusted over its neighbours'. */
private const val MIN_PAIRS_PER_HORIZON = 3

/** The longest horizon the LSTM publishes. */
private const val MAX_HORIZON = 24

/**
 * Mean absolute error at each horizon, scored from the app's own archive against
 * the readings that arrived afterwards. Keyed H+1..H+24 and always fully
 * populated, or empty when there is not enough history to be worth drawing.
 *
 * Horizons with too few scored hours borrow from their neighbours by
 * interpolation, and the whole curve is then smoothed: error grows with horizon,
 * so a band that jumps between one hour and the next is reporting how many samples
 * each hour happened to get, not how the model behaves.
 */
fun horizonErrors(
    archive: List<ArchivedForecast>,
    readings: List<Reading>,
    key: SeriesKey,
): Map<Int, Double> {
    if (archive.isEmpty()) return emptyMap()
    val actual = hourlyMeans(readings, key.kind, key.depthCm)
    if (actual.isEmpty()) return emptyMap()

    val byHorizon = HashMap<Int, MutableList<Double>>()
    var total = 0
    for (f in archive) {
        if (f.horizonH !in 1..MAX_HORIZON) continue
        val hour = f.targetMs - floorMod(f.targetMs, 3_600_000L)
        val real = actual[hour] ?: continue
        byHorizon.getOrPut(f.horizonH) { mutableListOf() } += abs(f.value - real)
        total++
    }
    if (total < MIN_PAIRS) return emptyMap()

    val known = byHorizon
        .filterValues { it.size >= MIN_PAIRS_PER_HORIZON }
        .mapValues { (_, errs) -> errs.average() }
    // Every horizon scored only once or twice: no shape to interpolate along, so
    // fall back to one flat figure rather than pretending to a per-hour curve.
    if (known.isEmpty()) {
        val flat = byHorizon.values.flatten().average()
        return (1..MAX_HORIZON).associateWith { flat }
    }

    val filled = (1..MAX_HORIZON).associateWith { h -> interpolate(known, h) }
    return (1..MAX_HORIZON).associateWith { h ->
        val window = ((h - 1)..(h + 1)).mapNotNull { filled[it] }
        window.average()
    }
}

/** Linear between the two nearest scored horizons; flat beyond the outermost. */
private fun interpolate(known: Map<Int, Double>, h: Int): Double {
    known[h]?.let { return it }
    val below = known.keys.filter { it < h }.maxOrNull()
    val above = known.keys.filter { it > h }.minOrNull()
    return when {
        below != null && above != null -> {
            val k = (h - below).toDouble() / (above - below)
            known.getValue(below) + (known.getValue(above) - known.getValue(below)) * k
        }
        below != null -> known.getValue(below)
        else -> known.getValue(above!!)
    }
}

private fun floorMod(a: Long, b: Long): Long {
    val r = a % b
    return if (r < 0) r + b else r
}

/** The station's next scheduled wake, whatever kind it is. */
fun List<StationEvent>.nextWakeAfter(nowMs: Long): StationEvent? =
    filter { it.tsMs > nowMs }.minByOrNull { it.tsMs }

/**
 * What the station is doing at this instant, in one line. It is asleep unless a
 * wake is due about now -- the app can only infer this from the schedule, and the
 * wording keeps that honest ("debería estar" rather than "está").
 */
fun currentStateLine(
    events: List<StationEvent>,
    config: ConfigSnapshotMsg,
    nowMs: Long,
): String {
    // "About now" is a 90 s window: the app can't watch the station, only reason
    // from its schedule, so anything tighter would claim more than it knows.
    val busy = events
        .minByOrNull { abs(it.tsMs - nowMs) }
        ?.takeIf { abs(it.tsMs - nowMs) < 90_000L }
    return when {
        busy != null -> "Despierta · ${busyWord(busy.kind)}"
        !config.deepSleep -> "Despierta y anunciándose (sueño profundo desactivado)"
        else -> "En reposo entre ciclos"
    }
}

private fun busyWord(kind: EventKind): String = when (kind) {
    EventKind.SAMPLE -> "leyendo sensores"
    EventKind.LORA_UP -> "enviando por LoRa"
    EventKind.LORA_DOWN -> "recibiendo un downlink"
    EventKind.LSTM -> "ejecutando el LSTM"
    EventKind.SYNC -> "poniendo el reloj en hora"
    EventKind.BOOT -> "arrancando"
}
