package com.astralink.terralink.ble

import kotlinx.coroutines.flow.Flow

actual class SaviaConnection {
    actual suspend fun read(characteristicUuid: String): ByteArray =
        throw BleError.NotSupported("BLE not supported on Desktop")

    actual suspend fun write(characteristicUuid: String, value: ByteArray, withResponse: Boolean) {
        throw BleError.NotSupported("BLE not supported on Desktop")
    }

    actual fun notifications(characteristicUuid: String): Flow<ByteArray> =
        throw BleError.NotSupported("BLE not supported on Desktop")

    actual suspend fun openL2cap(psm: Int): L2capChannel =
        throw BleError.NotSupported("BLE not supported on Desktop")

    actual suspend fun disconnect() {
        // No-op.
    }
}
