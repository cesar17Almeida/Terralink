// Replaying a real 48 h window from the training dataset into a station, so the
// LSTM can be exercised on data it was actually measured against instead of on
// the firmware's synthetic ramp.
//
// Everything here goes through channels the firmware already has: `ingest` for
// the soil series (same store as a probe reading), the weather characteristic for
// the air temperature (the ONLY place the model reads TA from), and `infer` to
// make it run now instead of at its daily hour. No firmware change.
package com.astralink.terralink.timeline

import com.astralink.terralink.ble.protocol.IngestPoint
import com.astralink.terralink.ble.protocol.Prediction
import com.astralink.terralink.ble.protocol.ReadingKind
import com.astralink.terralink.ble.session.ActiveSession
import com.astralink.terralink.state.ForecastArchive
import kotlinx.coroutines.delay
import kotlin.math.abs

const val REPLAY_PAST_HOURS = 48
const val REPLAY_FUTURE_HOURS = 24
private const val HOUR_MS = 3_600_000L

/** The port the replayed probe occupies. Port 1 is the station's first slot. */
private const val REPLAY_PORT = 1

/** One measured hour of the dataset. */
data class ReplayHour(
    val hs10: Double,
    val hs20: Double,
    val hs30: Double,
    val ta: Double,
)

/**
 * A window lifted out of `dataset_master_hourly.csv` by
 * `savia_c/tools/replay_lstm_dataset.py`: 48 measured hours, the 24 that follow
 * (their TA is the forecast the model is given, their HS30 is the truth it is
 * scored against), and what the same .tflite predicted for them on a host.
 *
 * [hostForecast] is what makes this worth doing: the station runs the SAME model
 * over the SAME numbers, so any difference between its answer and this one is the
 * board -- TFLM's kernels, the int8 arena, the fixed-point path -- and not the
 * data.
 */
data class ReplayWindow(
    val node: String,
    val startedAt: String,
    val past: List<ReplayHour>,
    val futureTa: List<Double>,
    val futureTruthHs30: List<Double>,
    val hostForecast: List<Double>,
    val hostMae: Double,
)

/** Parse the CSV the export script writes. Tolerant of blank cells by section. */
fun parseReplayWindow(csv: String): ReplayWindow {
    var node = "?"
    var started = "?"
    var hostMae = 0.0
    val past = mutableListOf<ReplayHour>()
    val futureTa = mutableListOf<Double>()
    val truth = mutableListOf<Double>()
    val hostPred = mutableListOf<Double>()

    for (raw in csv.lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        val f = line.split(",")
        if (line.startsWith("#")) {                       // "# nodo,4,inicio,...,mae_host,0.0086"
            // The comment marker rides on the first cell, so keys are compared with
            // it stripped -- otherwise the first key never matches and the metadata
            // is silently lost.
            for (i in f.indices) when (f[i].trim().removePrefix("#").trim()) {
                "nodo" -> node = f.getOrNull(i + 1)?.trim() ?: node
                "inicio" -> started = f.getOrNull(i + 1)?.trim() ?: started
                "mae_host" -> hostMae = f.getOrNull(i + 1)?.trim()?.toDoubleOrNull() ?: hostMae
            }
            continue
        }
        if (f[0] == "seccion") continue                   // header row
        when (f[0]) {
            "past" -> past += ReplayHour(
                hs10 = f[2].toDouble(), hs20 = f[3].toDouble(),
                hs30 = f[4].toDouble(), ta = f[5].toDouble(),
            )
            "future" -> { truth += f[4].toDouble(); futureTa += f[5].toDouble() }
            "host_pred" -> hostPred += f[4].toDouble()
        }
    }
    require(past.size == REPLAY_PAST_HOURS) { "la ventana necesita $REPLAY_PAST_HOURS horas pasadas, hay ${past.size}" }
    require(futureTa.size == REPLAY_FUTURE_HOURS) { "faltan horas de pronóstico de TA" }
    return ReplayWindow(node, started, past, futureTa, truth, hostPred, hostMae)
}

/** Where the replay is, so the UI can say what is happening instead of spinning. */
sealed interface ReplayStage {
    data class Step(val index: Int, val total: Int, val label: String) : ReplayStage
    data class Failed(val label: String, val message: String) : ReplayStage
    data object Done : ReplayStage
}

/** What the run produced, for the report the screen shows afterwards. */
data class ReplayReport(
    val anchorMs: Long,                       // the hour the window was aligned to end at
    val ingestedPast: Int,
    val ingestedTruth: Int,
    val inferenceQueued: Boolean,
    val stationForecast: List<Double>,        // 24 values, or empty if it never ran
    val hostForecast: List<Double>,
    val truth: List<Double>,
) {
    /** How the board's own run scores against the measured hours. */
    val stationMae: Double? = mae(stationForecast, truth)

    /** The host's run over the identical window, as the reference. */
    val hostMae: Double? = mae(hostForecast, truth)

    /** Board vs host on the same inputs: the size of the on-device deviation. */
    val stationVsHost: Double? = mae(stationForecast, hostForecast)

    private fun mae(a: List<Double>, b: List<Double>): Double? {
        val n = minOf(a.size, b.size)
        if (n == 0) return null
        var s = 0.0
        for (i in 0 until n) s += abs(a[i] - b[i])
        return s / n
    }
}

/**
 * Push the window, make the station infer, and collect what it said.
 *
 * The window is anchored so its newest measured hour IS the current hour of the
 * phone: the model refuses a window whose last step is a copy rather than a real
 * reading (LSTM_MAX_STALE_HOURS = 0), so anything older would simply be rejected.
 *
 * Requires the station's developer flag (`mock`) ON. It is what stops the
 * supervisor from sampling the real probe before it infers, which would replace
 * the newest injected hour with whatever the probe says -- or fail, on a station
 * with no probe wired.
 */
suspend fun runDatasetReplay(
    active: ActiveSession,
    stationId: String,
    window: ReplayWindow,
    nowMs: Long,
    onStage: (ReplayStage) -> Unit,
): ReplayReport {
    val total = 5
    // Align to the top of the current hour: the station aggregates by hour, so a
    // window ending mid-hour would put two of our steps in one bucket.
    val anchor = nowMs - nowMs % HOUR_MS

    onStage(ReplayStage.Step(1, total, "Inyectando 48 h de humedad medida"))
    val soil = buildList {
        window.past.forEachIndexed { i, h ->
            val ts = anchor - (REPLAY_PAST_HOURS - 1 - i) * HOUR_MS
            add(point(ts, h.hs10, 10))
            add(point(ts, h.hs20, 20))
            add(point(ts, h.hs30, 30))
        }
    }
    val pastAck = active.ingest(soil)

    onStage(ReplayStage.Step(2, total, "Enviando la temperatura del aire (48 h + 24 h)"))
    active.pushWeather(
        past = window.past.map { it.ta.toFloat() },
        future = window.futureTa.map { it.toFloat() },
    )

    onStage(ReplayStage.Step(3, total, "Pidiendo a la estación que ejecute el LSTM"))
    val queued = active.requestInference()

    onStage(ReplayStage.Step(4, total, "Esperando el pronóstico de la estación"))
    val forecast = if (queued) awaitForecast(active, anchor) else emptyList()
    if (forecast.isNotEmpty()) ForecastArchive.archive(stationId, forecast)

    onStage(ReplayStage.Step(5, total, "Inyectando el HS30 real de las 24 h siguientes"))
    val truthPoints = window.futureTruthHs30.mapIndexed { i, v ->
        point(anchor + (i + 1) * HOUR_MS, v, 30)
    }
    val truthAck = active.ingest(truthPoints)

    onStage(ReplayStage.Done)
    return ReplayReport(
        anchorMs = anchor,
        ingestedPast = pastAck.created + pastAck.updated,
        ingestedTruth = truthAck.created + truthAck.updated,
        inferenceQueued = queued,
        stationForecast = forecast.sortedBy { it.tsMs }.map { it.value },
        hostForecast = window.hostForecast,
        truth = window.futureTruthHs30,
    )
}

/**
 * Poll until a forecast that belongs to THIS run appears.
 *
 * "Belongs to this run" means its first target is after the anchor hour: the
 * station may still be holding a forecast from a previous run, and returning that
 * one would look like a success and score the wrong numbers.
 */
private suspend fun awaitForecast(
    active: ActiveSession,
    anchorMs: Long,
    tries: Int = 20,
    pollMs: Long = 1_000L,
): List<Prediction> {
    repeat(tries) {
        delay(pollMs)
        val run = runCatching { active.requestPredictions() }.getOrDefault(emptyList())
            .filter { it.kind == ForecastArchive.KIND_HS30 }
            .sortedBy { it.tsMs }
        if (run.isNotEmpty() && run.first().tsMs > anchorMs) return run
    }
    return emptyList()
}

private fun point(tsMs: Long, value: Double, depthCm: Int) = IngestPoint(
    tsMs = tsMs,
    kind = ReadingKind.SOIL_MOISTURE,
    value = value,
    depthCm = depthCm,
    port = REPLAY_PORT,
)
