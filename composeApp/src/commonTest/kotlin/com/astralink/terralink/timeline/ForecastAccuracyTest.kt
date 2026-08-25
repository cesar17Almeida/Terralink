package com.astralink.terralink.timeline

import com.astralink.terralink.ble.protocol.Reading
import com.astralink.terralink.ble.protocol.ReadingKind
import com.astralink.terralink.state.ArchivedForecast
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Scoring the model against reality. The station keeps only its newest forecast,
 * so everything here runs on the app's archive -- which means the pairing rules
 * (hourly buckets, newest run wins, unmeasured hours excluded) are the whole
 * correctness surface of the accuracy screen.
 */
class ForecastAccuracyTest {

    private val HOUR = 3_600_000L
    private val T0 = 1_787_616_000_000L   // 2026-08-25T00:00:00Z

    private fun reading(ts: Long, v: Double, depth: Int = 30) =
        Reading(tsMs = ts, port = 1, kind = ReadingKind.SOIL_MOISTURE, value = v, depthCm = depth)

    private fun forecast(issued: Long, target: Long, v: Double, h: Int) =
        ArchivedForecast(issuedMs = issued, targetMs = target, model = "lstm-hs30", value = v, horizonH = h)

    @Test
    fun readingsAreBucketedIntoHourlyMeansTheWayTheModelWorks() {
        // Three readings inside one hour are one step for an hourly model, not three.
        val readings = listOf(
            reading(T0 + 60_000L, 0.20),
            reading(T0 + 1_800_000L, 0.22),
            reading(T0 + 3_500_000L, 0.24),
        )
        val means = hourlyMeans(readings, ReadingKind.SOIL_MOISTURE, 30)
        assertEquals(1, means.size)
        assertEquals(0.22, means[T0]!!, 1e-9)
    }

    @Test
    fun depthMattersBecauseTheModelOnlySpeaksFor30cm() {
        val readings = listOf(reading(T0, 0.20, depth = 10), reading(T0, 0.30, depth = 30))
        assertEquals(0.30, hourlyMeans(readings, ReadingKind.SOIL_MOISTURE, 30)[T0]!!, 1e-9)
    }

    @Test
    fun theNewestRunWinsWhenTwoForecastsCoverTheSameHour() {
        val target = T0 + 5 * HOUR
        val forecasts = listOf(
            forecast(T0, target, 0.30, 5),                 // yesterday's guess
            forecast(T0 + 4 * HOUR, target, 0.24, 1),      // the run just before it
        )
        val pairs = pairForecast(forecasts, mapOf(target to 0.25))
        assertEquals(1, pairs.size)
        assertEquals(0.24, pairs.first().predicted, 1e-9)
        assertEquals(1, pairs.first().horizonH)
    }

    @Test
    fun hoursThatWereNeverMeasuredAreNotScored() {
        val forecasts = listOf(
            forecast(T0, T0 + HOUR, 0.30, 1),
            forecast(T0, T0 + 2 * HOUR, 0.31, 2),
        )
        val pairs = pairForecast(forecasts, mapOf((T0 + HOUR) to 0.29))
        assertEquals(1, pairs.size, "a prediction with nothing to compare to is not a score")
        assertEquals(T0 + HOUR, pairs.first().targetMs)
    }

    @Test
    fun aHorizonFilterKeepsOnlyThatStepOfEachRun() {
        val forecasts = listOf(
            forecast(T0, T0 + HOUR, 0.30, 1),
            forecast(T0, T0 + 2 * HOUR, 0.31, 2),
        )
        val actual = mapOf((T0 + HOUR) to 0.29, (T0 + 2 * HOUR) to 0.28)
        val pairs = pairForecast(forecasts, actual, preferHorizon = 2)
        assertEquals(listOf(2), pairs.map { it.horizonH })
    }

    @Test
    fun theStatsAreMaeRmseAndASignedBias() {
        val pairs = listOf(
            Paired(T0, predicted = 0.22, actual = 0.20, horizonH = 1, issuedMs = T0),
            Paired(T0 + HOUR, predicted = 0.18, actual = 0.20, horizonH = 2, issuedMs = T0),
            Paired(T0 + 2 * HOUR, predicted = 0.26, actual = 0.20, horizonH = 3, issuedMs = T0),
        )
        val stats = accuracyOf(pairs)!!
        assertEquals(3, stats.n)
        assertEquals((0.02 + 0.02 + 0.06) / 3, stats.mae, 1e-9)
        // Errors of +0.02, -0.02 and +0.06 average to a positive lean: the model
        // reads the soil wetter than it is.
        assertTrue(stats.bias > 0)
        assertEquals(0.06, abs(stats.worst!!.error), 1e-9)
        assertTrue(stats.rmse >= stats.mae, "RMSE punishes the bad hours harder than MAE")
    }

    @Test
    fun nothingToScoreYieldsNoStatsRatherThanZeroes() {
        assertEquals(null, accuracyOf(emptyList()))
    }

    @Test
    fun theSeriesSeparatesScoredHoursFromMeasuredOnesWithNoPrediction() {
        val readings = listOf(reading(T0, 0.20), reading(T0 + HOUR, 0.21))
        val forecasts = listOf(forecast(T0 - HOUR, T0, 0.22, 1))
        val series = buildAccuracySeries(forecasts, readings, ReadingKind.SOIL_MOISTURE, 30)
        assertEquals(1, series.pairs.size)
        assertEquals(listOf(T0 + HOUR), series.actualOnly.map { it.first })
    }
}
