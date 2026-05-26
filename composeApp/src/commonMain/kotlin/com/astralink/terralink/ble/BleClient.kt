package com.astralink.terralink.ble

import kotlinx.coroutines.flow.Flow

/**
 * Platform-independent BLE central. Implementations live in androidMain
 * (android.bluetooth.*), iosMain (CoreBluetooth), and jvmMain (stubs).
 *
 * Lifecycle:
 *   1. scan() emits devices matching the Savia primary service UUID.
 *   2. connect(deviceId) returns a SaviaConnection once GATT is up.
 *   3. close() releases native resources (scanner, central manager).
 *
 * All suspends throw BleError on failure.
 */
expect class BleClient() {
    /**
     * Scan for devices.
     *
     * `saviaOnly=true` (default) asks the OS to filter advertisements by
     * SAVIA_SERVICE_UUID so only Savia stations show up. Pass `false` to
     * scan every nearby BLE peripheral (used for the "show all" toggle in
     * the pairing UI); non-Savia results still arrive with `isSavia=false`
     * so the UI can render them disabled.
     */
    fun scan(saviaOnly: Boolean = true): Flow<ScannedDevice>

    suspend fun connect(deviceId: String): SaviaConnection

    suspend fun close()
}

/**
 * Result of a BLE scan. `id` is platform-defined and stable for the
 * lifetime of the OS BLE cache:
 *   - Android: MAC address ("AA:BB:CC:DD:EE:FF").
 *   - iOS: a CBPeripheral UUID string (the OS does not expose MAC).
 *
 * `isSavia` is the trustworthy signal -- the actual reads it from the
 * advertised service UUIDs in the scan record, not the name (which any
 * device can spoof). Use it to decide whether to allow connection.
 *
 * Pass `id` back to BleClient.connect() to open a connection.
 */
data class ScannedDevice(
    val id: String,
    val name: String?,
    val rssi: Int,
    val isSavia: Boolean,
)
