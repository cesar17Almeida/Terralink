package com.astralink.terralink.ble

import kotlinx.coroutines.flow.Flow

actual class L2capChannel {
    actual suspend fun send(data: ByteArray) {
        throw BleError.NotSupported("L2CAP not supported on Desktop")
    }

    actual fun received(): Flow<ByteArray> =
        throw BleError.NotSupported("L2CAP not supported on Desktop")

    actual suspend fun close() {
        // No-op.
    }
}
