package com.astralink.terralink.ble

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicWriteWithResponse
import platform.CoreBluetooth.CBCharacteristicWriteWithoutResponse
import platform.CoreBluetooth.CBL2CAPChannel
import platform.CoreBluetooth.CBL2CAPPSM
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID

@OptIn(ExperimentalForeignApi::class)
actual class SaviaConnection internal constructor(
    private val peripheral: CBPeripheral,
    private val delegate: IosPeripheralDelegate,
) {

    // CoreBluetooth serializes its callbacks, but we still need to gate
    // sequential read/write so the right pending continuation gets resumed.
    private val gattMutex = Mutex()

    actual suspend fun read(characteristicUuid: String): ByteArray = gattMutex.withLock {
        val char = findCharacteristic(characteristicUuid)
        suspendCancellableCoroutine { cont ->
            delegate.pendingRead = cont
            delegate.pendingReadUuid = characteristicUuid.lowercase()   // tells reads from notifies
            peripheral.readValueForCharacteristic(char)
        }
    }

    actual suspend fun write(
        characteristicUuid: String, value: ByteArray, withResponse: Boolean,
    ): Unit = gattMutex.withLock {
        val char = findCharacteristic(characteristicUuid)
        val type = if (withResponse) CBCharacteristicWriteWithResponse
                   else CBCharacteristicWriteWithoutResponse
        if (!withResponse) {
            // No callback fires for without-response writes; resolve immediately.
            peripheral.writeValue(value.toNSData(), forCharacteristic = char, type = type)
            return@withLock
        }
        suspendCancellableCoroutine<Unit> { cont ->
            delegate.pendingWrite = cont
            peripheral.writeValue(value.toNSData(), forCharacteristic = char, type = type)
        }
    }

    actual fun notifications(characteristicUuid: String): Flow<ByteArray> {
        val char = findCharacteristic(characteristicUuid)
        peripheral.setNotifyValue(true, forCharacteristic = char)
        return delegate.notificationFlow(characteristicUuid).asSharedFlow()
    }

    actual suspend fun openL2cap(psm: Int): L2capChannel {
        val channel = suspendCancellableCoroutine<CBL2CAPChannel> { cont ->
            delegate.pendingL2cap = cont
            peripheral.openL2CAPChannel(psm.toUShort())
        }
        return L2capChannel(channel)
    }

    actual suspend fun disconnect() {
        IosBle.central.cancelPeripheralConnection(peripheral)
        IosBle.forget(peripheral.identifier.UUIDString)
    }

    private fun findCharacteristic(uuid: String): CBCharacteristic {
        val target = CBUUID.UUIDWithString(uuid)
        val services = peripheral.services as List<CBService>? ?: emptyList()
        for (s in services) {
            val chars = s.characteristics as List<CBCharacteristic>? ?: continue
            chars.firstOrNull { it.UUID == target }?.let { return it }
        }
        throw BleError.NotFound("characteristic $uuid")
    }
}
