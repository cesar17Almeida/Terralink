package com.astralink.terralink.ble

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import platform.CoreBluetooth.CBL2CAPChannel
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSRunLoop
import platform.Foundation.NSStream
import platform.Foundation.NSStreamDelegateProtocol
import platform.Foundation.NSStreamEvent
import platform.Foundation.NSStreamEventEndEncountered
import platform.Foundation.NSStreamEventErrorOccurred
import platform.Foundation.NSStreamEventHasBytesAvailable
import platform.darwin.NSObject
import platform.posix.uint8_tVar

private const val READ_BUFFER = 4096

@OptIn(ExperimentalForeignApi::class)
actual class L2capChannel internal constructor(
    private val channel: CBL2CAPChannel,
) {

    private val received = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val streamDelegate = StreamDelegate(channel, received)

    init {
        channel.inputStream?.let { input ->
            input.delegate = streamDelegate
            input.scheduleInRunLoop(NSRunLoop.mainRunLoop, NSDefaultRunLoopMode)
            input.open()
        }
        channel.outputStream?.let { it.open() }
    }

    actual suspend fun send(data: ByteArray) {
        if (data.isEmpty()) return
        val output = channel.outputStream ?: throw BleError.IoError("L2CAP outputStream is nil")
        withContext(Dispatchers.Default) {
            data.usePinned { pinned ->
                var offset = 0
                while (offset < data.size) {
                    val written = output.write(
                        (pinned.addressOf(offset) as kotlinx.cinterop.CPointer<uint8_tVar>),
                        maxLength = (data.size - offset).convert(),
                    ).toInt()
                    if (written <= 0) {
                        throw BleError.IoError("L2CAP write returned $written")
                    }
                    offset += written
                }
            }
        }
    }

    actual fun received(): Flow<ByteArray> = received.asSharedFlow()

    actual suspend fun close() {
        channel.inputStream?.close()
        channel.outputStream?.close()
        channel.inputStream?.removeFromRunLoop(NSRunLoop.mainRunLoop, NSDefaultRunLoopMode)
    }
}

@OptIn(ExperimentalForeignApi::class)
private class StreamDelegate(
    private val channel: CBL2CAPChannel,
    private val sink: MutableSharedFlow<ByteArray>,
) : NSObject(), NSStreamDelegateProtocol {

    override fun stream(aStream: NSStream, handleEvent: NSStreamEvent) {
        when (handleEvent) {
            NSStreamEventHasBytesAvailable -> drainInput()
            NSStreamEventEndEncountered, NSStreamEventErrorOccurred -> {
                // Caller's `received()` Flow simply stops emitting; the
                // outer L2capChannel.close() will tear down the streams.
            }
        }
    }

    private fun drainInput() {
        val input = channel.inputStream ?: return
        val buf = ByteArray(READ_BUFFER)
        while (input.hasBytesAvailable) {
            buf.usePinned { pinned ->
                val read = input.read(
                    (pinned.addressOf(0) as kotlinx.cinterop.CPointer<uint8_tVar>),
                    maxLength = READ_BUFFER.convert(),
                ).toInt()
                if (read > 0) {
                    sink.tryEmit(buf.copyOf(read))
                }
            }
        }
    }
}
