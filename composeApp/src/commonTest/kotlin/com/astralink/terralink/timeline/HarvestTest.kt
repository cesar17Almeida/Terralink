package com.astralink.terralink.timeline

import com.astralink.terralink.ble.protocol.LoraStatus
import com.astralink.terralink.ble.protocol.Prediction
import com.astralink.terralink.ble.protocol.StatusMsg
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The status and the log ring report the same moments at different instants. A
 * harvest must turn them into one mark per event, and two harvests of the same
 * station must produce the same instants, or the journal fills with copies.
 */
class HarvestTest {

    // 2026-08-25T12:00:00Z.
    private val NOW = 1_787_659_200_000L
    private val H = 3_600_000L

    private fun status(nowMs: Long, uptimeS: Long, loraMs: Long?) = StatusMsg(
        v = 1, fw = "savia_c", nowMs = nowMs, uptimeS = uptimeS,
        lastSyncMs = NOW - 2 * H + 16_450L,
        lora = LoraStatus(inited = true, joined = true, rssi = -71, snr = 10.0, lastMs = loraMs),
    )

    private val cycleLogs = listOf(
        "10:00:00 LoRa: cycle due (period=3600s)",
        "10:00:09 LoRa: uplink forecast 4 B (unconfirmed)",
        "10:00:16 LoRa: signal RSSI -71 dBm, SNR 10.0 dB",
        "10:00:16 LoRa downlink: 8 B, 0 past + 0 future TA, clock set",
    )

    @Test
    fun theStatusAndTheLogOfOneCycleAreOneMarkPerLane() {
        // The status stamps the cycle in ms when it started; the log in seconds, later.
        val events = harvestEvents(status(NOW, 5000, NOW - 2 * H + 230L), emptyList(), emptyList(), cycleLogs)
        val up = events.filter { it.kind == EventKind.LORA_UP }
        val down = events.filter { it.kind == EventKind.LORA_DOWN }
        assertEquals(1, up.size)
        assertEquals(1, down.size)
        assertEquals(1, events.count { it.kind == EventKind.SYNC })
        // Earliest report, log words.
        assertEquals(NOW - 2 * H, up.first().tsMs)
        assertEquals("Uplink FORECAST · mínimo HS30 previsto · 4 B", up.first().detail)
        assertEquals(NOW - 2 * H + 230L, down.first().tsMs)
        assertEquals("Downlink · hora · RSSI -71 dBm · SNR 10.0 dB", down.first().detail)
    }

    @Test
    fun theStatusAloneKeepsTheSameInstantOnceTheLogHasRotated() {
        val first = harvestEvents(status(NOW, 5000, NOW - 2 * H + 230L), emptyList(), emptyList(), emptyList())
        assertEquals(NOW - 2 * H + 230L, first.single { it.kind == EventKind.LORA_UP }.tsMs)
    }

    @Test
    fun theModelRunIsOneMarkWhetherDerivedOrLogged() {
        // The forecast starts at the hour after the run's hour; the log says when it ran.
        val preds = (1..24).map { k ->
            Prediction(tsMs = NOW - 2 * H + k * H, model = "lstm-hs30", kind = "hs30_forecast", value = 0.2)
        }
        val logs = listOf(
            "10:20:00 sched: daily cycle (local 12:20, mode=local)",
            "10:20:01 inference: HS30 24h forecast stored (min=0.200)",
        )
        val lstm = harvestEvents(status(NOW, 5000, null), emptyList(), preds, logs)
            .filter { it.kind == EventKind.LSTM }
        assertEquals(1, lstm.size)
        assertEquals(NOW - 2 * H, lstm.first().tsMs)
    }

    @Test
    fun theBootEstimateJittersButStaysOneMarkPerHarvest() {
        // uptime_s is truncated, so now - uptime moves by up to a second between reads.
        val a = harvestEvents(status(NOW + 400, 5000, null), emptyList(), emptyList(), emptyList())
        val b = harvestEvents(status(NOW + 1_700, 5001, null), emptyList(), emptyList(), emptyList())
        val bootA = a.single { it.kind == EventKind.BOOT }.tsMs
        val bootB = b.single { it.kind == EventKind.BOOT }.tsMs
        assertEquals(300L, bootB - bootA)
        assertTrue(bootB - bootA <= EventKind.BOOT.sameEventWindowMs())
    }
}
