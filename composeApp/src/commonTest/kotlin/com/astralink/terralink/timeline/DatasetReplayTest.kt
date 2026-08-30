package com.astralink.terralink.timeline

import com.astralink.terralink.ble.codec.encode
import com.astralink.terralink.ble.protocol.MAX_CONTROL_MSG_BYTES
import com.astralink.terralink.ble.protocol.WeatherData
import com.astralink.terralink.ble.protocol.WeatherUpdateMsg
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * The dataset replay's two fragile points: parsing the exported window, and
 * getting a 72-value air-temperature message onto a characteristic whose
 * reassembly buffer is 512 B. Both fail silently in ways that would look like
 * "the model just didn't run".
 */
class DatasetReplayTest {

    private fun windowCsv(): String = buildString {
        appendLine("# nodo,4,inicio,2020-05-07T10:00:00,mae_host,0.008588")
        appendLine("seccion,h,hs10,hs20,hs30,ta")
        for (k in 0 until REPLAY_PAST_HOURS) {
            val v = 0.75 - k * 0.0005
            appendLine("past,${k - (REPLAY_PAST_HOURS - 1)},$v,${v + 0.01},${v - 0.01},${20.0 + k * 0.1}")
        }
        for (k in 1..REPLAY_FUTURE_HOURS) appendLine("future,$k,,,${0.72 - k * 0.001},${25.0 + k * 0.05}")
        for (k in 1..REPLAY_FUTURE_HOURS) appendLine("host_pred,$k,,,${0.721 - k * 0.001},")
    }

    @Test
    fun theExportedWindowParsesIntoItsThreeSeries() {
        val w = parseReplayWindow(windowCsv())
        assertEquals("4", w.node)
        assertEquals(REPLAY_PAST_HOURS, w.past.size)
        assertEquals(REPLAY_FUTURE_HOURS, w.futureTa.size)
        assertEquals(REPLAY_FUTURE_HOURS, w.futureTruthHs30.size)
        assertEquals(REPLAY_FUTURE_HOURS, w.hostForecast.size)
        assertEquals(0.008588, w.hostMae, 1e-9)
        // Oldest first: the firmware's window is ordered, and a reversed one would
        // feed the model a soil that is wetting instead of drying.
        assertTrue(w.past.first().hs30 > w.past.last().hs30)
    }

    @Test
    fun aShortWindowIsRefusedRatherThanPadded() {
        val truncated = windowCsv().lineSequence().take(20).joinToString("\n")
        assertFailsWith<IllegalArgumentException> { parseReplayWindow(truncated) }
    }

    @Test
    fun theFullWeatherMessageFitsTheFirmwareReassemblyBuffer() {
        // 48 + 24 values. As CBOR float32 this is ~400 B, inside both the codec's
        // MAX_CONTROL_MSG_BYTES and the firmware's 512 B weather_buf. Encoded as
        // doubles it would be ~650 B and the write would be refused -- which is the
        // regression this guards.
        val msg = WeatherUpdateMsg(
            data = WeatherData(
                pastTaHourly = List(48) { 20.0f + it * 0.1f },
                futureTaHourly = List(24) { 25.0f + it * 0.1f },
            ),
        )
        val bytes = encode(msg)
        assertTrue(
            bytes.size <= MAX_CONTROL_MSG_BYTES,
            "weather message is ${bytes.size} B, over the ${MAX_CONTROL_MSG_BYTES} B ceiling",
        )
        // Measured: 415 B (indefinite map, float32 values), verified against the
        // firmware's parser byte for byte.
        assertTrue(bytes.size < 512, "must fit the firmware's 512 B weather_buf, is ${bytes.size} B")
    }

    @Test
    fun theReportSeparatesBoardErrorFromModelError() {
        val truth = List(24) { 0.70 }
        val host = List(24) { 0.72 }          // the model is 0.02 high on this window
        val station = List(24) { 0.721 }      // the board adds 0.001 of its own
        val r = ReplayReport(
            anchorMs = 0, ingestedPast = 144, ingestedTruth = 24, inferenceQueued = true,
            stationForecast = station, hostForecast = host, truth = truth,
        )
        assertEquals(0.021, r.stationMae!!, 1e-9)
        assertEquals(0.020, r.hostMae!!, 1e-9)
        assertEquals(0.001, r.stationVsHost!!, 1e-9)
    }

    @Test
    fun aRunThatNeverProducedAForecastHasNoScores() {
        val r = ReplayReport(
            anchorMs = 0, ingestedPast = 144, ingestedTruth = 24, inferenceQueued = false,
            stationForecast = emptyList(), hostForecast = List(24) { 0.7 }, truth = List(24) { 0.7 },
        )
        assertEquals(null, r.stationMae)
        assertEquals(null, r.stationVsHost)
    }
}

/**
 * The window one load covers. These exist because getting them wrong is SILENT:
 * a bound that stops at "now" hides every future-stamped point, and the accuracy
 * chart then looks identical to "no data yet" -- which is exactly how the first
 * dataset replay came back empty despite the station having run the model.
 */
class LoadWindowTest {

    private val NOW = 1_787_659_200_000L

    @Test
    fun everyUpperBoundReachesPastNow() {
        val w = loadWindow(NOW)
        assertTrue(w.untilMs > NOW, "the local query must include future-stamped readings")
        assertTrue(w.fetchToMs > NOW, "the BLE fetch must ask the station for them too")
    }

    @Test
    fun theWindowCoversAFullForecastHorizonAhead() {
        // A forecast reaches 24 h out, and its ground truth is measured over those
        // same hours: anything shorter cannot score a whole run.
        val w = loadWindow(NOW)
        val horizonMs = 24 * 3_600_000L
        assertTrue(w.untilMs - NOW >= horizonMs)
        assertTrue(w.fetchToMs - NOW >= horizonMs)
    }

    @Test
    fun theLocalStoreReachesFurtherBackThanTheStationDoes() {
        // The point of journalling: the app holds more history than the station,
        // which only keeps ~48 h.
        val w = loadWindow(NOW)
        assertTrue(w.fromMs < w.fetchFromMs)
    }
}
