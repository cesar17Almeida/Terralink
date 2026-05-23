package com.astralink.terralink.ble

import kotlinx.coroutines.flow.Flow

/**
 * L2CAP Connection-oriented Channel. Bidirectional byte stream over BLE.
 *
 * send: write the full ByteArray to the peer; suspends until OS accepts it.
 * received: emits inbound chunks as the peer sends them. Completes when
 *   the peer closes its end (or close() is called).
 * close: release the channel.
 *
 * On iOS this wraps CBL2CAPChannel + its NSInputStream / NSOutputStream.
 * On Android, a BluetoothSocket from BluetoothDevice.createL2capChannel(psm).
 */
expect class L2capChannel {
    suspend fun send(data: ByteArray)

    fun received(): Flow<ByteArray>

    suspend fun close()
}
