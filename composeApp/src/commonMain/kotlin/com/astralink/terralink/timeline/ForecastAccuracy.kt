package com.astralink.terralink.timeline

import com.astralink.terralink.ble.protocol.Reading
import com.astralink.terralink.state.ArchivedForecast
import kotlin.math.abs
import kotlin.math.sqrt

private const val HOUR_MS = 3_600_000L

/** A predicted hour set against the hour that actually happened. */
data class Paired(
    val targetMs: Long,
    val predicted: Double,
    val actual: Double,
    val horizonH: Int,
    val issuedMs: Long,
) {
    /** Signed, in the model's own units (VWC 0..1). Positive = the model read wet. */
    val error: Double get() = predicted - actual
}

/** How the model did over a set of pairs. All in VWC 0..1, as the model works. */
data class AccuracyStats(
    val n: Int,
    val mae: Double,
    val rmse: Double,
    val bias: Double,       // mean signed error: over- (+) or under-prediction (-)
    val worst: Paired?,
)

/** The measured series next to a run's prediction, ready to plot. */
data class AccuracySeries(
    val pairs: List<Paired>,
    val actualOnly: List<Pair<Long, Double>>,   // measured hours with nothing predicted for them
    val stats: AccuracyStats?,
)

/**
 * Reduce raw readings to hourly means of one soil-moisture depth -- the same
 * bucketing the station's own aggregation does, and the resolution the LSTM works
 * at. Comparing an hourly prediction against an instantaneous reading would score
 * the model on noise it was never asked to predict.
 */
fun hourlyMeans(readings: List<Reading>, kind: String, depthCm: Int?): Map<Long, Double> =
    readings
        .filter { it.kind == kind && (depthCm == null || it.depthCm == depthCm) }
        .groupBy { it.tsMs - floorMod(it.tsMs, HOUR_MS) }
        .mapValues { (_, group) -> group.sumOf { it.value } / group.size }

/**
 * Pair each archived forecast point with the hour it was about.
 *
 * When several runs predicted the same hour, [preferHorizon] picks which one
 * counts: `null` keeps the newest run (the model's best guess for that hour), an
 * integer keeps the H+n step from every run, which is how you see accuracy decay
 * with horizon -- H+1 should be much better than H+24, and the screen lets the
 * user ask that question.
 */
fun pairForecast(
    forecasts: List<ArchivedForecast>,
    actualByHour: Map<Long, Double>,
    preferHorizon: Int? = null,
): List<Paired> {
    val candidates = if (preferHorizon == null) forecasts else forecasts.filter { it.horizonH == preferHorizon }
    return candidates
        .groupBy { it.targetMs - floorMod(it.targetMs, HOUR_MS) }
        .mapNotNull { (hour, runs) ->
            val chosen = runs.maxByOrNull { it.issuedMs } ?: return@mapNotNull null
            val actual = actualByHour[hour] ?: return@mapNotNull null
            Paired(
                targetMs = hour,
                predicted = chosen.value,
                actual = actual,
                horizonH = chosen.horizonH,
                issuedMs = chosen.issuedMs,
            )
        }
        .sortedBy { it.targetMs }
}

/** MAE / RMSE / bias over [pairs]; null when there is nothing to score. */
fun accuracyOf(pairs: List<Paired>): AccuracyStats? {
    if (pairs.isEmpty()) return null
    val n = pairs.size
    val mae = pairs.sumOf { abs(it.error) } / n
    val rmse = sqrt(pairs.sumOf { it.error * it.error } / n)
    val bias = pairs.sumOf { it.error } / n
    return AccuracyStats(
        n = n, mae = mae, rmse = rmse, bias = bias,
        worst = pairs.maxByOrNull { abs(it.error) },
    )
}

/** Everything the accuracy screen plots, in one pass over both sources. */
fun buildAccuracySeries(
    forecasts: List<ArchivedForecast>,
    readings: List<Reading>,
    kind: String,
    depthCm: Int?,
    preferHorizon: Int? = null,
): AccuracySeries {
    val actual = hourlyMeans(readings, kind, depthCm)
    val pairs = pairForecast(forecasts, actual, preferHorizon)
    val paired = pairs.map { it.targetMs }.toSet()
    return AccuracySeries(
        pairs = pairs,
        actualOnly = actual.filterKeys { it !in paired }.toList().sortedBy { it.first },
        stats = accuracyOf(pairs),
    )
}

/** The part of a run that hasn't happened yet: predicted, nothing to compare to. */
fun pendingOf(forecasts: List<ArchivedForecast>, nowMs: Long): List<ArchivedForecast> =
    forecasts.filter { it.targetMs > nowMs }.sortedBy { it.targetMs }

private fun floorMod(a: Long, b: Long): Long {
    val r = a % b
    return if (r < 0) r + b else r
}
