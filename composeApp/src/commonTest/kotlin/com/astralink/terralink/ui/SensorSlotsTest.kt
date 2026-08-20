package com.astralink.terralink.ui

import com.astralink.terralink.ble.protocol.SensorInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The sensor table is slot-addressed: a port names the slot and never moves. These
 * guard the two places the app could silently renumber it -- deleting a sensor and
 * picking the slot for a new one. Getting this wrong splices one sensor's stored
 * history onto another's, because readings are keyed by port on both sides.
 */
class SensorSlotsTest {

    private fun sensor(port: Int, gpio: Int, type: String = "dht11") =
        SensorInfo(port = port, gpio = gpio, type = type, addr = "")

    @Test
    fun deletingASensorKeepsTheOtherPorts() {
        val sensors = listOf(sensor(1, 2), sensor(2, 6), sensor(3, 8))

        // Remove the middle one: the third must stay on port 3, not slide to 2.
        val table = sensorTableWithout(sensors, index = 1)
        assertEquals(listOf(1, 3), table.map { it.port })
        assertEquals(listOf(2, 8), table.map { it.gpio })
    }

    @Test
    fun deletingTheFirstSensorDoesNotPullTheRestDown() {
        val sensors = listOf(sensor(1, 2), sensor(2, 6))
        val table = sensorTableWithout(sensors, index = 0)
        assertEquals(listOf(2), table.map { it.port })
        assertEquals(listOf(6), table.map { it.gpio })
    }

    @Test
    fun aNewSensorTakesTheLowestFreeSlot() {
        assertEquals(1, firstFreePort(emptyList()))
        assertEquals(3, firstFreePort(listOf(sensor(1, 2), sensor(2, 6))))
        // The hole a delete left is the first thing reused.
        assertEquals(2, firstFreePort(listOf(sensor(1, 2), sensor(3, 8))))
    }

    @Test
    fun aFullTableOffersNoSlot() {
        val full = (1..6).map { sensor(it, gpio = it) }
        assertNull(firstFreePort(full))
    }
}
