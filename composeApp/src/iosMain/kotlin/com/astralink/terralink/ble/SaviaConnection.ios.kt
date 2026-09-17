package com.astralink.terralink.ble

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicWriteWithResponse
import platform.CoreBluetooth.CBCharacteristicWriteWithoutResponse
import platform.CoreBluetooth.CBL2CAPChannel
import platform.CoreBluetooth.CBL2CAPPSM
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralStateConnected
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID

// CoreBluetooth never answers a command sent to a dropped link, so every wait is bounded.
private const val GATT_OP_TIMEOUT_MS = 10_000L

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
        ensureConnected()
        withTimeoutOrNull(GATT_OP_TIMEOUT_MS) {
            suspendCancellableCoroutine<ByteArray> { cont ->
                cont.invokeOnCancellation {
                    delegate.pendingRead = null
                    delegate.pendingReadUuid = null
                }
                delegate.pendingRead = cont
                delegate.pendingReadUuid = characteristicUuid.lowercase()   // tells reads from notifies
                peripheral.readValueForCharacteristic(char)
            }
        } ?: throw BleError.Timeout("read $characteristicUuid: no answer from the station")
    }

    actual suspend fun write(
        characteristicUuid: String, value: ByteArray, withResponse: Boolean,
    ): Unit = gattMutex.withLock {
        val char = findCharacteristic(characteristicUuid)
        ensureConnected()
        val type = if (withResponse) CBCharacteristicWriteWithResponse
                   else CBCharacteristicWriteWithoutResponse
        if (!withResponse) {
            // No callback fires for without-response writes; resolve immediately.
            peripheral.writeValue(value.toNSData(), forCharacteristic = char, type = type)
            return@withLock
        }
        withTimeoutOrNull(GATT_OP_TIMEOUT_MS) {
            suspendCancellableCoroutine<Unit> { cont ->
                cont.invokeOnCancellation { delegate.pendingWrite = null }
                delegate.pendingWrite = cont
                peripheral.writeValue(value.toNSData(), forCharacteristic = char, type = type)
            }
        } ?: throw BleError.Timeout("write $characteristicUuid: no answer from the station")
    }

    actual fun notifications(characteristicUuid: String): Flow<ByteArray> {
        val char = findCharacteristic(characteristicUuid)
        ensureConnected()
        peripheral.setNotifyValue(true, forCharacteristic = char)
        return delegate.notificationFlow(characteristicUuid).asSharedFlow()
    }

    actual suspend fun openL2cap(psm: Int): L2capChannel {
        ensureConnected()
        val channel = withTimeoutOrNull(GATT_OP_TIMEOUT_MS) {
            suspendCancellableCoroutine<CBL2CAPChannel> { cont ->
                cont.invokeOnCancellation { delegate.pendingL2cap = null }
                delegate.pendingL2cap = cont
                peripheral.openL2CAPChannel(psm.toUShort())
            }
        } ?: throw BleError.Timeout("L2CAP psm=$psm: no answer from the station")
        return L2capChannel(channel)
    }

    actual suspend fun disconnect() {
        IosBle.central.cancelPeripheralConnection(peripheral)
        IosBle.forget(peripheral.identifier.UUIDString)
    }

    private fun ensureConnected() {
        if (peripheral.state != CBPeripheralStateConnected) {
            throw BleError.Disconnected("the station is no longer connected")
        }
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
