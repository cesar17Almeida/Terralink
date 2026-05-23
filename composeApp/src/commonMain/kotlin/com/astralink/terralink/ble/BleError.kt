package com.astralink.terralink.ble

/**
 * Errors thrown by BleClient / SaviaConnection / L2capChannel.
 *
 * Sealed so callers can pattern-match (`when (e)` exhaustively) and react
 * to specific failure modes (permission prompt, retry, surface to UI...).
 */
sealed class BleError(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    class NotSupported(message: String) : BleError(message)
    class PermissionDenied(message: String) : BleError(message)
    class BluetoothOff(message: String = "Bluetooth is turned off") : BleError(message)
    class NotFound(deviceId: String) : BleError("device not found: $deviceId")
    class Disconnected(message: String = "disconnected") : BleError(message)
    class Timeout(message: String) : BleError(message)
    class GattError(message: String, val status: Int) : BleError("$message (status=$status)")
    class IoError(message: String, cause: Throwable? = null) : BleError(message, cause)
}
