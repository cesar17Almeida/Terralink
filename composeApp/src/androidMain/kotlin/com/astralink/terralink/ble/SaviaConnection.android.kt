package com.astralink.terralink.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.resumeWithException

// Standard Client Characteristic Configuration Descriptor UUID.
private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

// Longest wait for one GATT callback; a silently dropped link would otherwise hang the caller.
private const val GATT_OP_TIMEOUT_MS = 10_000L

@SuppressLint("MissingPermission")
actual class SaviaConnection internal constructor(
    private val gatt: BluetoothGatt,
    private val callback: AndroidGattCallback,
    private val device: BluetoothDevice,
) {

    // Serializes GATT operations: Android allows at most one read/write in flight.
    private val gattMutex = Mutex()

    // Runs the queued CCCD writes; cancelled on disconnect.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
        awaitGatt<ByteArray>("read $characteristicUuid", clear = { callback.pendingRead = null }) { cont ->
            callback.pendingRead = cont
            if (gatt.readCharacteristic(char)) null else "readCharacteristic returned false"
        }
    }

    actual suspend fun write(
        characteristicUuid: String, value: ByteArray, withResponse: Boolean,
    ): Unit = gattMutex.withLock {
        val char = findCharacteristic(characteristicUuid)
        val writeType = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        awaitGatt<Unit>("write $characteristicUuid", clear = { callback.pendingWrite = null }) { cont ->
            callback.pendingWrite = cont
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val rc = gatt.writeCharacteristic(char, value, writeType)
                if (rc == BluetoothStatusCodes.SUCCESS) null else "writeCharacteristic rc=$rc"
            } else {
                @Suppress("DEPRECATION") run {
                    char.value = value
                    char.writeType = writeType
                    if (gatt.writeCharacteristic(char)) null else "writeCharacteristic returned false"
                }
            }
        }
    }

    actual fun notifications(characteristicUuid: String): Flow<ByteArray> {
        val char = findCharacteristic(characteristicUuid)
        // Non-suspend by contract (ActiveSession binds it from property initializers).
        if (!gatt.setCharacteristicNotification(char, true)) {
            throw BleError.IoError("setCharacteristicNotification($characteristicUuid) returned false")
        }
        val cccd = char.getDescriptor(CCCD_UUID)
            ?: throw BleError.IoError("characteristic $characteristicUuid has no CCCD descriptor")
        // The CCCD write is a GATT operation too: two back to back are refused as busy,
        // so it queues on the same mutex (UNDISPATCHED takes its FIFO place right now).
        val enabled = scope.async(start = CoroutineStart.UNDISPATCHED) {
            gattMutex.withLock { writeCccd(characteristicUuid, cccd) }
        }
        val notes = callback.notificationFlow(characteristicUuid)
        return channelFlow {
            launch { enabled.await() }      // a refused enable fails the collector
            notes.collect { send(it) }
        }
    }

    private suspend fun writeCccd(uuid: String, cccd: BluetoothGattDescriptor) =
        awaitGatt<Unit>("enable notify $uuid", clear = { callback.pendingDescriptorWrite = null }) { cont ->
            callback.pendingDescriptorWrite = cont
            val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val rc = gatt.writeDescriptor(cccd, enable)
                if (rc == BluetoothStatusCodes.SUCCESS) null else "writeDescriptor rc=$rc"
            } else {
                @Suppress("DEPRECATION") run {
                    cccd.value = enable
                    if (gatt.writeDescriptor(cccd)) null else "writeDescriptor returned false"
                }
            }
        }

    /** Start one GATT operation and wait for its callback; [start] returns an error text if refused. */
    private suspend fun <T : Any> awaitGatt(
        what: String,
        clear: () -> Unit,
        start: (CancellableContinuation<T>) -> String?,
    ): T = withTimeoutOrNull(GATT_OP_TIMEOUT_MS) {
        suspendCancellableCoroutine<T> { cont ->
            cont.invokeOnCancellation { clear() }
            val failure = start(cont)
            if (failure != null) {
                clear()
                cont.resumeWithException(BleError.IoError(failure))
            }
        }
    } ?: throw BleError.Timeout("$what: no answer from the station")

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
        scope.cancel()
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
