package com.astralink.terralink.timeline

import com.astralink.terralink.ble.protocol.ConfigSnapshotMsg
import com.astralink.terralink.ble.protocol.DeviceInfo
import com.astralink.terralink.ble.protocol.SensorInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The future half of the timeline is computed on the phone from the station's
 * config, so it is only as good as its agreement with `scheduler_tick` /
 * `lora_cycle` in the firmware. These pin the three rules that agreement rests on:
 * cadences anchor to the last thing that happened (not to a clock boundary), the
 * daily cycle fires once per LOCAL day, and the LoRa period is clamped before use.
 */
class ScheduleTest {

    private val HOUR = 3_600_000L
    private val DAY = 86_400_000L

    private fun config(
        captureS: Int = 3600,
        loraPeriodS: Int = 3600,
        dailyHour: Int = 20,
        dailyMin: Int = 0,
        offsetMin: Int = 120,
        sensors: List<SensorInfo> = listOf(
            SensorInfo(port = 1, gpio = 2, type = "sdi12_aquacheck", addr = "0"),
        ),
        mode: String = "local",
    ) = ConfigSnapshotMsg(
        device = DeviceInfo(model = "Pico", mcu = "RP2040", fw = "test"),
        sleepS = 60,
        deepSleep = true,
        captureS = captureS,
        dailyHour = dailyHour,
        dailyMin = dailyMin,
        wakeGpio = 15,
        loraPeriodS = loraPeriodS,
        inferenceMode = mode,
        utcOffsetMin = offsetMin,
        sensors = sensors,
    )

    // A round UTC instant to anchor the arithmetic on: 2026-08-25T00:00:00Z.
    private val T0 = 1_787_616_000_000L

    @Test
    fun capturesAnchorOnTheLastSampleNotOnTheHour() {
        // Last sample 20 min past the hour: the next one is 20 min past the NEXT
        // hour, because the firmware counts from its own due time, not from :00.
        val last = T0 + 20 * 60_000L
        val events = projectSchedule(
            config(),
            ScheduleAnchors(nowMs = T0 + 30 * 60_000L, lastSampleByPort = mapOf(1 to last)),
            untilMs = T0 + 3 * HOUR,
        ).filter { it.kind == EventKind.SAMPLE }

        assertEquals(last + HOUR, events.first().tsMs)
        assertEquals(last + 2 * HOUR, events[1].tsMs)
    }

    @Test
    fun capturesCatchUpPastAMissedWindow() {
        // The station was unreachable for five hours: the projection must not draw
        // the four wakes it already missed, only the next real one.
        val last = T0
        val now = T0 + 5 * HOUR + 10 * 60_000L
        val next = projectSchedule(
            config(),
            ScheduleAnchors(nowMs = now, lastSampleByPort = mapOf(1 to last)),
            untilMs = now + 2 * HOUR,
        ).first { it.kind == EventKind.SAMPLE }

        assertTrue(next.tsMs > now, "a projected capture must be in the future")
        assertEquals(T0 + 6 * HOUR, next.tsMs)
    }

    @Test
    fun perSensorIntervalOverridesTheGlobalCadence() {
        val sensors = listOf(
            SensorInfo(port = 1, gpio = 2, type = "sdi12_aquacheck", addr = "0", intervalS = 900),
            SensorInfo(port = 2, gpio = 6, type = "dht11", addr = ""),
        )
        val events = projectSchedule(
            config(captureS = 3600, sensors = sensors),
            ScheduleAnchors(nowMs = T0, lastSampleByPort = mapOf(1 to T0, 2 to T0)),
            untilMs = T0 + HOUR,
        ).filter { it.kind == EventKind.SAMPLE }

        assertEquals(listOf(T0 + 900_000L, T0 + 1_800_000L, T0 + 2_700_000L),
            events.filter { it.port == 1 }.map { it.tsMs })
        assertEquals(emptyList(), events.filter { it.port == 2 }.map { it.tsMs })
    }

    @Test
    fun outputSlotsAreNeverSampled() {
        val sensors = listOf(SensorInfo(port = 1, gpio = 2, type = "actuator", addr = ""))
        val events = projectSchedule(
            config(sensors = sensors),
            ScheduleAnchors(nowMs = T0),
            untilMs = T0 + DAY,
        )
        assertTrue(events.none { it.kind == EventKind.SAMPLE },
            "an actuator has nothing to measure, so it must not appear as a capture")
    }

    @Test
    fun theLoraPeriodIsClampedTheWayTheFirmwareClampsIt() {
        // 60 s is below SAVIA_LORA_PERIOD_MIN_S: the firmware raises it to 300 s,
        // and so must the projection, or we'd promise uplinks that never happen.
        val events = projectSchedule(
            config(loraPeriodS = 60),
            ScheduleAnchors(nowMs = T0, lastUplinkMs = T0),
            untilMs = T0 + 900_000L,
        ).filter { it.kind == EventKind.LORA_UP }

        assertEquals(listOf(T0 + 300_000L, T0 + 600_000L), events.map { it.tsMs })
    }

    @Test
    fun theDailyCycleFiresTodayWhenItsLocalTimeHasNotArrived() {
        // 20:00 local with a +02:00 offset is 18:00 UTC. At 06:00 UTC it is still
        // ahead of us today.
        val next = nextDailyMs(
            nowMs = T0 + 6 * HOUR, hour = 20, minute = 0, offsetMin = 120, lastDailyMs = null,
        )
        assertEquals(T0 + 18 * HOUR, next)
    }

    @Test
    fun theDailyCycleMovesToTomorrowOnceItHasFiredToday() {
        val firedToday = T0 + 18 * HOUR
        val next = nextDailyMs(
            nowMs = firedToday + HOUR, hour = 20, minute = 0, offsetMin = 120,
            lastDailyMs = firedToday,
        )
        assertEquals(firedToday + DAY, next)
    }

    @Test
    fun anOverdueDailyCycleIsDueNowRatherThanLost() {
        // Past 20:00 local and nothing fired today: the firmware's ">=" test catches
        // up on its next tick, so the projection must not push it to tomorrow.
        val now = T0 + 19 * HOUR
        val next = nextDailyMs(
            nowMs = now, hour = 20, minute = 0, offsetMin = 120, lastDailyMs = null,
        )
        assertEquals(now, next)
    }

    @Test
    fun theDailyCycleDragsAFullCaptureSweepWithIt() {
        val sensors = listOf(
            SensorInfo(port = 1, gpio = 2, type = "sdi12_aquacheck", addr = "0"),
            SensorInfo(port = 2, gpio = 6, type = "dht11", addr = ""),
        )
        val events = projectSchedule(
            config(sensors = sensors, captureS = 86_400),
            ScheduleAnchors(nowMs = T0 + 6 * HOUR, lastSampleByPort = mapOf(1 to T0, 2 to T0)),
            untilMs = T0 + 20 * HOUR,
        )
        val daily = events.filter { it.tsMs == T0 + 18 * HOUR }
        assertEquals(1, daily.count { it.kind == EventKind.LSTM })
        assertEquals(setOf(1, 2), daily.filter { it.kind == EventKind.SAMPLE }.map { it.port }.toSet())
    }

    @Test
    fun forwardModeStillSchedulesTheCycleButSaysTheModelRunsElsewhere() {
        val events = projectSchedule(
            config(mode = "forward"),
            ScheduleAnchors(nowMs = T0 + 6 * HOUR),
            untilMs = T0 + 20 * HOUR,
        )
        val lstm = events.first { it.kind == EventKind.LSTM }
        assertTrue(lstm.detail.contains("reenvío"), "forward mode must not claim on-device inference")
    }
}
