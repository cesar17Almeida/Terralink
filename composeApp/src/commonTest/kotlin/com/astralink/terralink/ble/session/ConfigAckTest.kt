package com.astralink.terralink.ble.session

import com.astralink.terralink.ble.protocol.ConfigPatchMsg
import com.astralink.terralink.ble.protocol.ConfigSnapshotMsg
import com.astralink.terralink.ble.protocol.DeviceInfo
import com.astralink.terralink.ble.protocol.SensorInfo
import com.astralink.terralink.ble.protocol.SensorPatch
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Station snapshot to diff patches against: name "Parcela", one AquaCheck on GP2. */
private fun snapshot(
    name: String = "Parcela",
    sleepS: Int = 300,
    lat: Double? = 39.47,
    lon: Double? = -0.37,
    sensors: List<SensorInfo> = listOf(
        SensorInfo(port = 1, gpio = 2, type = "sdi12_aquacheck", addr = "0"),
    ),
    dailyHour: Int = 20,
    dailyMin: Int = 0,
) = ConfigSnapshotMsg(
    device = DeviceInfo(model = "Raspberry Pi Pico 2 W", mcu = "RP2350", fw = "0.1.0-c"),
    name = name,
    sleepS = sleepS,
    deepSleep = false,
    dailyHour = dailyHour,
    dailyMin = dailyMin,
    wakeGpio = 15,
    lat = lat,
    lon = lon,
    sensors = sensors,
)

class ConfigAckTest {

    // The confirmation must name ONLY what the save carried. Reporting the sleep
    // time or the power saving after a prediction-time change reads as if those had
    // been rewritten too, which is exactly what a sparse patch avoids doing.
    @Test
    fun summaryNamesOnlyWhatChanged() {
        val applied = snapshot(dailyHour = 20, dailyMin = 30)
        val text = ConfigPatchMsg(dailyHour = 20, dailyMin = 30).appliedSummary(applied)
        assertEquals("Aplicado ✓ · predicción a las 20:30", text)
        assertFalse(text.contains("sueño"))
        assertFalse(text.contains("ahorro"))
    }

    // Values come from the station's confirmed snapshot, not from what we asked for.
    @Test
    fun summaryReadsValuesFromTheSnapshot() {
        val applied = snapshot(sleepS = 600)
        val text = ConfigPatchMsg(sleepS = 300).appliedSummary(applied)
        assertEquals("Aplicado ✓ · sueño 10 min", text)
    }

    // A minute-only save still describes the whole time, which is what the user reads.
    @Test
    fun summaryDescribesTheWholeTimeForAMinuteOnlyChange() {
        val applied = snapshot(dailyHour = 6, dailyMin = 15)
        assertEquals(
            "Aplicado ✓ · predicción a las 06:15",
            ConfigPatchMsg(dailyMin = 15).appliedSummary(applied),
        )
    }

    // Past a handful of changes the list stops being readable, so it collapses.
    @Test
    fun summaryCollapsesWhenManySettingsChange() {
        val text = ConfigPatchMsg(
            name = "Huerta", sleepS = 600, deepSleep = true, captureS = 3600,
        ).appliedSummary(snapshot())
        assertEquals("Aplicado ✓ · 4 ajustes", text)
    }

    @Test
    fun sensorRejectionNamesThePortNotTheSlotIndex() {
        // The firmware reports the 0-based slot; the user only ever sees ports.
        val text = configErrorText("sensor 1: pin occupied")
        assertTrue(text.startsWith("Sensor 2:"), text)
        assertContains(text, "ya lo usa otro sensor")
    }

    @Test
    fun knownFieldReasonsAreTranslated() {
        assertContains(configErrorText("sleep_s out of range"), "Tiempo de sueño")
        assertContains(configErrorText("auth required"), "bloqueada")
        assertContains(configErrorText("no_local_inference"), "modelo embebido")
    }

    @Test
    fun unknownReasonSurvivesVerbatim() {
        // A newer firmware must never be silenced by an older app.
        assertContains(configErrorText("brand_new_field out of range"), "brand_new_field out of range")
    }

    @Test
    fun appliedPatchReportsNothingMissing() {
        val patch = ConfigPatchMsg(name = "Parcela", sleepS = 300)
        assertEquals(emptyList(), patch.notAppliedIn(snapshot()))
    }

    @Test
    fun revertedFieldsAreListed() {
        // What a silently refused save looks like: the station kept its old values.
        val patch = ConfigPatchMsg(name = "Huerto", sleepS = 60)
        val missing = ConfigPatchMsg(name = patch.name, sleepS = patch.sleepS).notAppliedIn(snapshot())
        assertEquals(listOf("el nombre", "el tiempo de sueño"), missing)
    }

    @Test
    fun coordsTolerateTheE7RoundTrip() {
        // Coordinates travel as degrees x 1e-7, so the read-back is rounded, not equal.
        val patch = ConfigPatchMsg(lat = 39.4700001, lon = -0.3700001)
        assertEquals(emptyList(), patch.notAppliedIn(snapshot()))
        assertEquals(listOf("las coordenadas"), ConfigPatchMsg(lat = 40.0, lon = -0.37).notAppliedIn(snapshot()))
    }

    @Test
    fun sensorTableIsComparedByTypeAndPins() {
        val patch = ConfigPatchMsg(
            sensors = listOf(
                SensorPatch(port = 1, gpio = 2, type = "sdi12_aquacheck", addr = "0"),
                SensorPatch(port = 2, gpio = 6, type = "dht11"),
            ),
        )
        // The station still reports the single old slot -> the swap never happened.
        assertEquals(listOf("los sensores"), patch.notAppliedIn(snapshot()))

        val applied = snapshot(
            sensors = listOf(
                SensorInfo(port = 1, gpio = 2, type = "sdi12_aquacheck", addr = "0"),
                SensorInfo(port = 2, gpio = 6, type = "dht11", addr = ""),
            ),
        )
        assertEquals(emptyList(), patch.notAppliedIn(applied))

        // Same sensors, different SLOTS: the station did not honour the ports it
        // was given, so the patch is not applied. A positional compare said "ok".
        val movedSlots = snapshot(
            sensors = listOf(
                SensorInfo(port = 2, gpio = 2, type = "sdi12_aquacheck", addr = "0"),
                SensorInfo(port = 3, gpio = 6, type = "dht11", addr = ""),
            ),
        )
        assertEquals(listOf("los sensores"), patch.notAppliedIn(movedSlots))

        // A table with a hole verifies when the station reports the same ports,
        // whatever order they arrive in.
        val holed = ConfigPatchMsg(
            sensors = listOf(
                SensorPatch(port = 1, gpio = 2, type = "sdi12_aquacheck", addr = "0"),
                SensorPatch(port = 3, gpio = 6, type = "dht11"),
            ),
        )
        val holedSnapshot = snapshot(
            sensors = listOf(
                SensorInfo(port = 3, gpio = 6, type = "dht11", addr = ""),
                SensorInfo(port = 1, gpio = 2, type = "sdi12_aquacheck", addr = "0"),
            ),
        )
        assertEquals(emptyList(), holed.notAppliedIn(holedSnapshot))
    }
}
