package com.astralink.terralink.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Adapts BluetoothGattCallback (Android's main-thread callbacks) into
 * suspending APIs and Flows. One callback per active GATT connection.
 *
 * Threading: callbacks fire on the Binder thread; we only assign volatile-ish
 * fields and resume continuations. SaviaConnection's Mutex serializes the
 * GATT operations (read/write) on its side so there is at most one pending
 * `*Read` / `*Write` continuation at a time.
 */
internal class AndroidGattCallback : BluetoothGattCallback() {

    companion object {
        // Negotiate the largest practical ATT MTU so control writes (32-byte
        // @ByteString fields, ~158-byte ingest batches) fit in one write; the
        // Android default of 23 leaves only 20 usable payload bytes.
        const val ATT_MTU_TARGET = 247
    }

    var gatt: BluetoothGatt? = null
    var device: BluetoothDevice? = null

    var pendingConnect: CancellableContinuation<SaviaConnection>? = null
    var pendingRead: CancellableContinuation<ByteArray>? = null
    var pendingWrite: CancellableContinuation<Unit>? = null
    var pendingDescriptorWrite: CancellableContinuation<Unit>? = null

    // ConcurrentHashMap so onCharacteristicChanged (Binder thread) reads are safe
    // against getOrPut writes from the constructing coroutine thread.
    private val notifyFlows = java.util.concurrent.ConcurrentHashMap<String, MutableSharedFlow<ByteArray>>()

    fun notificationFlow(uuid: String): Flow<ByteArray> {
        val key = uuid.lowercase()
        val flow = notifyFlows.getOrPut(key) {
            MutableSharedFlow(
                replay = 0,
                extraBufferCapacity = 64,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        }
        return flow.asSharedFlow()
    }

    override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                g.discoverServices()
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                val err = BleError.Disconnected("disconnected (status=$status)")
                pendingConnect?.takeIf { it.isActive }?.resumeWithException(err)
                pendingConnect = null
                pendingRead?.takeIf { it.isActive }?.resumeWithException(err)
                pendingRead = null
                pendingWrite?.takeIf { it.isActive }?.resumeWithException(err)
                pendingWrite = null
                pendingDescriptorWrite?.takeIf { it.isActive }?.resumeWithException(err)
                pendingDescriptorWrite = null
            }
        }
    }

    override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
        val cont = pendingConnect
        if (cont == null || !cont.isActive) {
            pendingConnect = null
            return
        }
        val dev = device
        if (status != BluetoothGatt.GATT_SUCCESS) {
            pendingConnect = null
            cont.resumeWithException(BleError.GattError("service discovery failed", status))
        } else if (dev == null) {
            pendingConnect = null
            cont.resumeWithException(BleError.IoError("device handle missing on connect"))
        } else {
            // Negotiate MTU before completing connect; resume happens in onMtuChanged.
            // If the request can't be issued, resume now rather than hang the handshake.
            if (!g.requestMtu(ATT_MTU_TARGET)) finishConnect(g)
        }
    }

    override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
        // MTU outcome is best-effort: complete connect regardless of negotiated size.
        finishConnect(g)
    }

    // Resumes the connect continuation with a ready SaviaConnection. Idempotent:
    // clears pendingConnect so a late onMtuChanged after timeout/cancel is a no-op.
    private fun finishConnect(g: BluetoothGatt) {
        val cont = pendingConnect
        pendingConnect = null
        if (cont == null || !cont.isActive) return
        val dev = device
        if (dev == null) cont.resumeWithException(BleError.IoError("device handle missing on connect"))
        else cont.resume(SaviaConnection(g, this, dev))
    }

    // API 33+ overload (preferred). The value lands here without needing to read it back off the characteristic.
    override fun onCharacteristicRead(
        g: BluetoothGatt, char: BluetoothGattCharacteristic, value: ByteArray, status: Int,
    ) {
        completeRead(value, status)
    }

    // Pre-33 overload kept for backwards compatibility (minSdk 24).
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onCharacteristicRead(
        g: BluetoothGatt, char: BluetoothGattCharacteristic, status: Int,
    ) {
        completeRead(char.value ?: ByteArray(0), status)
    }

    private fun completeRead(value: ByteArray, status: Int) {
        val cont = pendingRead
        pendingRead = null
        if (cont == null || !cont.isActive) return
        if (status == BluetoothGatt.GATT_SUCCESS) cont.resume(value)
        else cont.resumeWithException(BleError.GattError("read failed", status))
    }

    override fun onCharacteristicWrite(
        g: BluetoothGatt, char: BluetoothGattCharacteristic, status: Int,
    ) {
        val cont = pendingWrite
        pendingWrite = null
        if (cont == null || !cont.isActive) return
        if (status == BluetoothGatt.GATT_SUCCESS) cont.resume(Unit)
        else cont.resumeWithException(BleError.GattError("write failed", status))
    }

    override fun onDescriptorWrite(
        g: BluetoothGatt, descriptor: android.bluetooth.BluetoothGattDescriptor, status: Int,
    ) {
        val cont = pendingDescriptorWrite
        pendingDescriptorWrite = null
        if (cont == null || !cont.isActive) return
        if (status == BluetoothGatt.GATT_SUCCESS) cont.resume(Unit)
        else cont.resumeWithException(BleError.GattError("descriptor write failed", status))
    }

    override fun onCharacteristicChanged(
        g: BluetoothGatt, char: BluetoothGattCharacteristic, value: ByteArray,
    ) {
        notifyFlows[char.uuid.toString().lowercase()]?.tryEmit(value)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onCharacteristicChanged(g: BluetoothGatt, char: BluetoothGattCharacteristic) {
        notifyFlows[char.uuid.toString().lowercase()]?.tryEmit(char.value ?: return)
    }
}
