package com.astralink.terralink.ble

import kotlinx.coroutines.flow.Flow

private const val NOT_SUPPORTED =
    "BLE is not supported on the Desktop (JVM) build. Use the Android or iOS app."

actual class BleClient {
    actual fun scan(): Flow<ScannedDevice> =
        throw BleError.NotSupported(NOT_SUPPORTED)

    actual suspend fun connect(deviceId: String): SaviaConnection =
        throw BleError.NotSupported(NOT_SUPPORTED)

    actual suspend fun close() {
        // No-op; nothing to release on Desktop.
    }
}
