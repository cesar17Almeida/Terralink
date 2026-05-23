package com.astralink.terralink.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.astralink.terralink.ble.protocol.SAVIA_SERVICE_UUID
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

@SuppressLint("MissingPermission")
actual class BleClient {

    private val ctx: Context get() = AndroidBleContext.appContext

    private val adapter: BluetoothAdapter
        get() = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: throw BleError.NotSupported("Bluetooth adapter not available on this device")

    actual fun scan(): Flow<ScannedDevice> = callbackFlow {
        if (!adapter.isEnabled) {
            close(BleError.BluetoothOff())
            return@callbackFlow
        }
        val scanner = adapter.bluetoothLeScanner
            ?: run {
                close(BleError.NotSupported("BLE scanner unavailable"))
                return@callbackFlow
            }
        // Filter on the Savia primary service UUID so we don't get every BLE device nearby.
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid.fromString(SAVIA_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(
                    ScannedDevice(
                        id = result.device.address,
                        name = runCatching { result.device.name }.getOrNull(),
                        rssi = result.rssi,
                    )
                )
            }

            override fun onScanFailed(errorCode: Int) {
                close(BleError.IoError("scan failed with error $errorCode"))
            }
        }
        scanner.startScan(listOf(filter), settings, callback)
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    actual suspend fun connect(deviceId: String): SaviaConnection {
        if (!adapter.isEnabled) throw BleError.BluetoothOff()
        val device = try {
            adapter.getRemoteDevice(deviceId)
        } catch (e: IllegalArgumentException) {
            throw BleError.NotFound(deviceId)
        }
        return SaviaConnection.connect(ctx, device)
    }

    actual suspend fun close() {
        // No persistent state to release.
    }
}
