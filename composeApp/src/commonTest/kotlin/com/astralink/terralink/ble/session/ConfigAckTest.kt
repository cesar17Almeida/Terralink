package com.astralink.terralink.ble.session

import com.astralink.terralink.ble.protocol.ConfigPatchMsg
import com.astralink.terralink.ble.protocol.ConfigSnapshotMsg
import com.astralink.terralink.ble.protocol.DeviceInfo
import com.astralink.terralink.ble.protocol.SensorInfo
import com.astralink.terralink.ble.protocol.SensorPatch
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
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
) = ConfigSnapshotMsg(
    device = DeviceInfo(model = "Raspberry Pi Pico 2 W", mcu = "RP2350", fw = "0.1.0-c"),
    name = name,
    sleepS = sleepS,
    deepSleep = false,
    wakeGpio = 15,
    lat = lat,
    lon = lon,
    sensors = sensors,
)

class ConfigAckTest {

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
                SensorPatch(gpio = 2, type = "sdi12_aquacheck", addr = "0"),
                SensorPatch(gpio = 6, type = "dht11"),
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
    }
}
