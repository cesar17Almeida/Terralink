package com.astralink.terralink.ble.session

import com.astralink.terralink.ble.BleClient
import com.astralink.terralink.ble.ScannedDevice
import kotlinx.coroutines.flow.Flow

/**
 * High-level facade over BleClient. The UI layer constructs one of these,
 * scans, connects, and from that point on talks to the returned
 * ActiveSession. Single-instance per app is fine; the underlying BleClient
 * is cheap.
 */
class SaviaSession(
    private val client: BleClient = BleClient(),
) {

    /**
     * Scan for devices. `saviaOnly=true` filters by the Savia service UUID
     * (default for pairing); `false` returns every BLE peripheral (used by
     * the "Show all" toggle in the scan UI).
     */
    fun scan(saviaOnly: Boolean = true): Flow<ScannedDevice> = client.scan(saviaOnly)

    /** Open a GATT connection. Discovers services + characteristics. */
    suspend fun connect(deviceId: String): ActiveSession {
        val connection = client.connect(deviceId)
        return ActiveSession(connection)
    }

    /** Release the underlying client (no-op on most platforms). */
    suspend fun close() {
        client.close()
    }
}
