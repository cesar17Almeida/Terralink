package com.astralink.terralink.ble

import kotlinx.coroutines.flow.Flow

/**
 * Active GATT connection to a Savia device.
 *
 * read / write are suspending and target a characteristic UUID; notifications
 * exposes a hot flow that emits every notify on the given characteristic
 * (the underlying subscribe is taken on first collect, dropped on cancel).
 *
 * openL2cap opens an L2CAP CoC against the connected peer on `psm`, which
 * the peer announced via the blob_control ready message.
 */
expect class SaviaConnection {
    suspend fun read(characteristicUuid: String): ByteArray

    suspend fun write(characteristicUuid: String, value: ByteArray, withResponse: Boolean = true)

    fun notifications(characteristicUuid: String): Flow<ByteArray>

    suspend fun openL2cap(psm: Int): L2capChannel

    suspend fun disconnect()
}
