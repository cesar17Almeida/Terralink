package com.astralink.terralink.ble

import com.astralink.terralink.ble.protocol.SAVIA_SERVICE_UUID
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCentralManagerStatePoweredOff
import platform.CoreBluetooth.CBCentralManagerStatePoweredOn
import platform.CoreBluetooth.CBCentralManagerStateUnauthorized
import platform.CoreBluetooth.CBCentralManagerStateUnsupported
import platform.CoreBluetooth.CBL2CAPChannel
import platform.CoreBluetooth.CBL2CAPPSM
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.CoreBluetooth.CBCharacteristic
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSUUID
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * App-wide singleton that owns a single CBCentralManager and dispatches
 * delegate callbacks to per-peripheral state. Created lazily on first use.
 *
 * Threading: CoreBluetooth callbacks run on the queue we hand to the manager
 * (null = main). We resume coroutines directly from those callbacks; the
 * coroutine framework moves the resumption to whichever dispatcher the
 * caller was on.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosBleManager : NSObject(), CBCentralManagerDelegateProtocol {

    val central: CBCentralManager by lazy {
        CBCentralManager(delegate = this, queue = null)
    }

    val scanFlow: MutableSharedFlow<ScannedDevice> = MutableSharedFlow(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val pendingConnects: MutableMap<String, CancellableContinuation<CBPeripheral>> = mutableMapOf()
    // Keep a strong reference to peripherals we want to interact with; CoreBluetooth otherwise discards them.
    private val activePeripherals: MutableMap<String, CBPeripheral> = mutableMapOf()

    fun registerPendingConnect(peripheralId: String, cont: CancellableContinuation<CBPeripheral>) {
        pendingConnects[peripheralId] = cont
    }

    fun cancelPendingConnect(peripheralId: String) {
        pendingConnects.remove(peripheralId)
    }

    fun retain(peripheral: CBPeripheral) {
        activePeripherals[peripheral.identifier.UUIDString] = peripheral
    }

    fun forget(peripheralId: String) {
        activePeripherals.remove(peripheralId)
    }

    fun ensureReady() {
        when (central.state) {
            CBCentralManagerStatePoweredOff -> throw BleError.BluetoothOff()
            CBCentralManagerStateUnauthorized -> throw BleError.PermissionDenied(
                "Bluetooth permission denied; check Settings -> TerraLink -> Bluetooth"
            )
            CBCentralManagerStateUnsupported -> throw BleError.NotSupported("BLE unsupported on this device")
            CBCentralManagerStatePoweredOn -> Unit  // ready
            else -> throw BleError.IoError("Bluetooth manager not ready (state=${central.state})")
        }
    }

    // --- CBCentralManagerDelegate ----

    override fun centralManagerDidUpdateState(central: CBCentralManager) {
        // Drained on demand by ensureReady(). Nothing to push here.
    }

    override fun centralManager(
        central: CBCentralManager,
        didDiscoverPeripheral: CBPeripheral,
        advertisementData: Map<Any?, *>,
        RSSI: NSNumber,
    ) {
        scanFlow.tryEmit(
            ScannedDevice(
                id = didDiscoverPeripheral.identifier.UUIDString,
                name = didDiscoverPeripheral.name,
                rssi = RSSI.intValue,
            )
        )
    }

    override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
        val id = didConnectPeripheral.identifier.UUIDString
        pendingConnects.remove(id)?.takeIf { it.isActive }?.resume(didConnectPeripheral)
    }

    @ObjCSignatureOverride
    override fun centralManager(
        central: CBCentralManager,
        didFailToConnectPeripheral: CBPeripheral,
        error: NSError?,
    ) {
        val id = didFailToConnectPeripheral.identifier.UUIDString
        pendingConnects.remove(id)?.takeIf { it.isActive }?.resumeWithException(
            BleError.IoError("connect failed: ${error?.localizedDescription ?: "unknown"}")
        )
    }

    @ObjCSignatureOverride
    override fun centralManager(
        central: CBCentralManager,
        didDisconnectPeripheral: CBPeripheral,
        error: NSError?,
    ) {
        val id = didDisconnectPeripheral.identifier.UUIDString
        // Surface to any pending connect (e.g. timed out mid-connect).
        pendingConnects.remove(id)?.takeIf { it.isActive }?.resumeWithException(
            BleError.Disconnected("disconnected: ${error?.localizedDescription ?: "unknown"}")
        )
        forget(id)
    }
}

/**
 * Per-peripheral delegate. Owns the pending continuations for read/write,
 * the notification flows, and the L2CAP open handshake. One instance per
 * SaviaConnection.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosPeripheralDelegate : NSObject(), CBPeripheralDelegateProtocol {

    var pendingDiscoverServices: CancellableContinuation<Unit>? = null
    var pendingRead: CancellableContinuation<ByteArray>? = null
    var pendingWrite: CancellableContinuation<Unit>? = null
    var pendingL2cap: CancellableContinuation<CBL2CAPChannel>? = null

    private val notifyFlows: MutableMap<String, MutableSharedFlow<ByteArray>> = mutableMapOf()

    fun notificationFlow(uuid: String): MutableSharedFlow<ByteArray> =
        notifyFlows.getOrPut(uuid.lowercase()) {
            MutableSharedFlow(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        }

    override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
        val cont = pendingDiscoverServices ?: return
        pendingDiscoverServices = null
        if (didDiscoverServices != null) {
            cont.resumeWithException(BleError.IoError("discoverServices: ${didDiscoverServices.localizedDescription}"))
        } else {
            // Kick off characteristic discovery for the Savia service.
            val saviaUuid = CBUUID.UUIDWithString(SAVIA_SERVICE_UUID)
            val service = (peripheral.services as List<CBService>?)?.firstOrNull { it.UUID == saviaUuid }
            if (service == null) {
                cont.resumeWithException(BleError.NotFound("Savia primary service not found on peer"))
            } else {
                // We resume once characteristics also land; reuse the same continuation slot.
                pendingDiscoverServices = cont
                peripheral.discoverCharacteristics(null, forService = service)
            }
        }
    }

    override fun peripheral(
        peripheral: CBPeripheral,
        didDiscoverCharacteristicsForService: CBService,
        error: NSError?,
    ) {
        val cont = pendingDiscoverServices ?: return
        pendingDiscoverServices = null
        if (error != null) {
            cont.resumeWithException(BleError.IoError("discoverCharacteristics: ${error.localizedDescription}"))
        } else {
            cont.resume(Unit)
        }
    }

    @ObjCSignatureOverride
    override fun peripheral(
        peripheral: CBPeripheral,
        didUpdateValueForCharacteristic: CBCharacteristic,
        error: NSError?,
    ) {
        val char = didUpdateValueForCharacteristic
        val data = char.value?.toByteArray() ?: ByteArray(0)
        if (char.isNotifying) {
            notifyFlows[char.UUID.UUIDString.lowercase()]?.tryEmit(data)
        } else {
            val cont = pendingRead
            pendingRead = null
            if (cont != null && cont.isActive) {
                if (error != null) cont.resumeWithException(BleError.IoError("read: ${error.localizedDescription}"))
                else cont.resume(data)
            }
        }
    }

    @ObjCSignatureOverride
    override fun peripheral(
        peripheral: CBPeripheral,
        didWriteValueForCharacteristic: CBCharacteristic,
        error: NSError?,
    ) {
        val cont = pendingWrite
        pendingWrite = null
        if (cont == null || !cont.isActive) return
        if (error != null) cont.resumeWithException(BleError.IoError("write: ${error.localizedDescription}"))
        else cont.resume(Unit)
    }

    override fun peripheral(
        peripheral: CBPeripheral,
        didOpenL2CAPChannel: CBL2CAPChannel?,
        error: NSError?,
    ) {
        val cont = pendingL2cap
        pendingL2cap = null
        if (cont == null || !cont.isActive) return
        val ch = didOpenL2CAPChannel
        when {
            error != null -> cont.resumeWithException(BleError.IoError("openL2CAPChannel: ${error.localizedDescription}"))
            ch == null -> cont.resumeWithException(BleError.IoError("openL2CAPChannel: nil channel returned"))
            else -> cont.resume(ch)
        }
    }
}
