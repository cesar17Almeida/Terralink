package com.astralink.terralink.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlin.coroutines.resumeWithException

// Standard Client Characteristic Configuration Descriptor UUID.
private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

@SuppressLint("MissingPermission")
actual class SaviaConnection internal constructor(
    private val gatt: BluetoothGatt,
    private val callback: AndroidGattCallback,
    private val device: BluetoothDevice,
) {

    // Serializes GATT operations: Android allows at most one read/write in flight.
    private val gattMutex = Mutex()

    companion object {
        suspend fun connect(ctx: Context, device: BluetoothDevice): SaviaConnection =
            suspendCancellableCoroutine { cont ->
                val callback = AndroidGattCallback()
                callback.pendingConnect = cont
                callback.device = device
                val g = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    device.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
                } else {
                    @Suppress("DEPRECATION")
                    device.connectGatt(ctx, false, callback)
                }
                if (g == null) {
                    callback.pendingConnect = null
                    cont.resumeWithException(BleError.IoError("connectGatt returned null"))
                    return@suspendCancellableCoroutine
                }
                callback.gatt = g
                cont.invokeOnCancellation { runCatching { g.close() } }
            }
    }

    actual suspend fun read(characteristicUuid: String): ByteArray = gattMutex.withLock {
        val char = findCharacteristic(characteristicUuid)
        suspendCancellableCoroutine { cont ->
            callback.pendingRead = cont
            val ok = gatt.readCharacteristic(char)
            if (!ok) {
                callback.pendingRead = null
                cont.resumeWithException(BleError.IoError("readCharacteristic returned false"))
            }
        }
    }

    actual suspend fun write(
        characteristicUuid: String, value: ByteArray, withResponse: Boolean,
    ): Unit = gattMutex.withLock {
        val char = findCharacteristic(characteristicUuid)
        val writeType = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        suspendCancellableCoroutine<Unit> { cont ->
            callback.pendingWrite = cont
            val failure: String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val rc = gatt.writeCharacteristic(char, value, writeType)
                if (rc == BluetoothStatusCodes.SUCCESS) null else "writeCharacteristic rc=$rc"
            } else {
                @Suppress("DEPRECATION") run {
                    char.value = value
                    char.writeType = writeType
                    if (gatt.writeCharacteristic(char)) null else "writeCharacteristic returned false"
                }
            }
            if (failure != null) {
                callback.pendingWrite = null
                cont.resumeWithException(BleError.IoError(failure))
            }
        }
    }

    actual fun notifications(characteristicUuid: String): Flow<ByteArray> {
        val char = findCharacteristic(characteristicUuid)
        // The Flow itself is hot/shared in the callback. Enable is side-effect-only
        // here (the contract keeps notifications() non-suspend so ActiveSession can
        // bind it from a property initializer), but a failed/absent CCCD write must
        // surface now -- otherwise the subscribe silently never enables.
        if (!gatt.setCharacteristicNotification(char, true)) {
            throw BleError.IoError("setCharacteristicNotification($characteristicUuid) returned false")
        }
        val cccd = char.getDescriptor(CCCD_UUID)
            ?: throw BleError.IoError("characteristic $characteristicUuid has no CCCD descriptor")
        val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val failure: String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val rc = gatt.writeDescriptor(cccd, enable)
            if (rc == BluetoothStatusCodes.SUCCESS) null else "writeDescriptor rc=$rc"
        } else {
            @Suppress("DEPRECATION") run {
                cccd.value = enable
                if (gatt.writeDescriptor(cccd)) null else "writeDescriptor returned false"
            }
        }
        if (failure != null) throw BleError.IoError("enable notify $characteristicUuid: $failure")
        return callback.notificationFlow(characteristicUuid)
    }

    actual suspend fun openL2cap(psm: Int): L2capChannel {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw BleError.NotSupported(
                "L2CAP CoC requires Android 10 (API 29); current is ${Build.VERSION.SDK_INT}"
            )
        }
        val socket = try {
            device.createL2capChannel(psm)
        } catch (e: Throwable) {
            throw BleError.IoError("createL2capChannel($psm) failed: ${e.message}", e)
        }
        try {
            socket.connect()
        } catch (e: Throwable) {
            runCatching { socket.close() }
            throw BleError.IoError("L2CAP connect on psm=$psm failed: ${e.message}", e)
        }
        return L2capChannel(socket)
    }

    actual suspend fun disconnect() {
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
    }

    private fun findCharacteristic(uuid: String): BluetoothGattCharacteristic {
        val target = UUID.fromString(uuid)
        for (service in gatt.services) {
            service.getCharacteristic(target)?.let { return it }
        }
        throw BleError.NotFound("characteristic $uuid")
    }
}
