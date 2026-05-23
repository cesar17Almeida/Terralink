package com.astralink.terralink.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private const val READ_BUFFER = 4096

@SuppressLint("MissingPermission")
actual class L2capChannel internal constructor(
    private val socket: BluetoothSocket,
) {

    actual suspend fun send(data: ByteArray) {
        withContext(Dispatchers.IO) {
            socket.outputStream.write(data)
            socket.outputStream.flush()
        }
    }

    actual fun received(): Flow<ByteArray> = flow {
        val buffer = ByteArray(READ_BUFFER)
        while (currentCoroutineContext().isActive) {
            val read = try {
                socket.inputStream.read(buffer)
            } catch (e: Throwable) {
                throw BleError.IoError("L2CAP read failed: ${e.message}", e)
            }
            if (read < 0) break
            if (read > 0) emit(buffer.copyOf(read))
        }
    }.flowOn(Dispatchers.IO)

    actual suspend fun close() {
        withContext(Dispatchers.IO) {
            runCatching { socket.close() }
        }
    }
}
