package com.astralink.terralink.ble

import com.astralink.terralink.ble.protocol.SAVIA_SERVICE_UUID
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSUUID

@OptIn(ExperimentalForeignApi::class)
actual class BleClient {

    actual fun scan(saviaOnly: Boolean): Flow<ScannedDevice> = callbackFlow {
        try {
            IosBle.ensureReady()
        } catch (e: BleError) {
            close(e); return@callbackFlow
        }

        // Subscribe to scan results that the shared manager pushes.
        val job = launch {
            IosBle.scanFlow.collect { trySend(it) }
        }

        // saviaOnly -> filter list with our service UUID. !saviaOnly -> nil
        // service UUIDs argument so iOS reports every advertisement.
        val serviceUUIDs: List<CBUUID>? = if (saviaOnly) {
            listOf(CBUUID.UUIDWithString(SAVIA_SERVICE_UUID))
        } else null
        IosBle.central.scanForPeripheralsWithServices(serviceUUIDs, options = null)
        awaitClose {
            IosBle.central.stopScan()
            job.cancel()
        }
    }

    actual suspend fun connect(deviceId: String): SaviaConnection {
        IosBle.ensureReady()
        // The CoreBluetooth binding declares NSUUID(uUIDString:) as non-null,
        // but an invalid string would crash inside Foundation. Callers should
        // pass IDs that came from a ScannedDevice / a previous connection.
        val nsuuid = NSUUID(uUIDString = deviceId)

        // CoreBluetooth lets us look up a peripheral by its identifier.
        val peripherals = IosBle.central
            .retrievePeripheralsWithIdentifiers(listOf(nsuuid))
        val peripheral = (peripherals as List<CBPeripheral>?)?.firstOrNull()
            ?: throw BleError.NotFound("peripheral $deviceId not in CoreBluetooth cache; scan first")

        IosBle.retain(peripheral)

        // 1. Connect.
        suspendCancellableCoroutine<CBPeripheral> { cont ->
            IosBle.registerPendingConnect(deviceId, cont)
            cont.invokeOnCancellation {
                IosBle.cancelPendingConnect(deviceId)
                IosBle.central.cancelPeripheralConnection(peripheral)
            }
            IosBle.central.connectPeripheral(peripheral, options = null)
        }

        // 2. Discover services + characteristics.
        val periphDelegate = IosPeripheralDelegate()
        peripheral.delegate = periphDelegate
        suspendCancellableCoroutine<Unit> { cont ->
            periphDelegate.pendingDiscoverServices = cont
            peripheral.discoverServices(listOf(CBUUID.UUIDWithString(SAVIA_SERVICE_UUID)))
        }

        return SaviaConnection(peripheral, periphDelegate)
    }

    actual suspend fun close() {
        // Shared manager lives for the app's lifetime; nothing to release.
    }
}
