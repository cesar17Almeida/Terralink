package com.astralink.terralink.ble.session

import com.astralink.terralink.ble.SaviaConnection
import com.astralink.terralink.ble.codec.chunkedDecode
import com.astralink.terralink.ble.codec.decode
import com.astralink.terralink.ble.codec.encode
import com.astralink.terralink.ble.protocol.BlobControlEnvelope
import com.astralink.terralink.ble.protocol.BlobKind
import com.astralink.terralink.ble.protocol.BlobStartMsg
import com.astralink.terralink.ble.protocol.CHR_BLOB_CONTROL_UUID
import com.astralink.terralink.ble.protocol.CHR_DATA_REQUEST_UUID
import com.astralink.terralink.ble.protocol.CHR_DATA_RESPONSE_UUID
import com.astralink.terralink.ble.protocol.CHR_STATUS_UUID
import com.astralink.terralink.ble.protocol.CHR_TIME_SYNC_UUID
import com.astralink.terralink.ble.protocol.CHR_WEATHER_UUID
import com.astralink.terralink.ble.protocol.DataChunkMsg
import com.astralink.terralink.ble.protocol.DataRequestMsg
import com.astralink.terralink.ble.protocol.Op
import com.astralink.terralink.ble.protocol.StatusMsg
import com.astralink.terralink.ble.protocol.TimeSyncMsg
import com.astralink.terralink.ble.protocol.WeatherData
import com.astralink.terralink.ble.protocol.WeatherMsg
import com.astralink.terralink.ble.util.sha256
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile

private const val L2CAP_CHUNK_BYTES = 4096

/**
 * Operations on an established GATT connection to Savia. Methods talk
 * the wire protocol defined in `ble/protocol/` and reassemble notify
 * chunks where needed.
 */
class ActiveSession internal constructor(
    val connection: SaviaConnection,
) {

    // Hot Flows: each platform impl actually fires StartNotify + writes
    // the CCCD on first subscriber. Eagerly bind them so the response
    // notification can't fire before we subscribe.
    private val dataResponseFlow: Flow<ByteArray> =
        connection.notifications(CHR_DATA_RESPONSE_UUID)
    private val blobControlFlow: Flow<ByteArray> =
        connection.notifications(CHR_BLOB_CONTROL_UUID)

    // --- Simple ops --------------------------------------------------------

    suspend fun readStatus(): StatusMsg =
        decode(connection.read(CHR_STATUS_UUID))

    suspend fun setTime(epochMs: Long) {
        connection.write(CHR_TIME_SYNC_UUID, encode(TimeSyncMsg(ms = epochMs)))
    }

    suspend fun setWeather(data: WeatherData) {
        connection.write(CHR_WEATHER_UUID, encode(WeatherMsg(data = data)))
    }

    // --- Data query --------------------------------------------------------

    /**
     * Issue a data_request and collect data_response chunks until eof.
     * Returns the reassembled raw payload (CBOR bytes); callers decode
     * it into the concrete shape (list of readings, aggregations, ...).
     */
    suspend fun requestData(
        kind: String,
        fromMs: Long? = null,
        toMs: Long? = null,
        limit: Int? = null,
    ): ByteArray = coroutineScope {
        val collected = mutableListOf<ByteArray>()

        // Subscribe before writing the request so we don't miss the first chunk.
        val collector = async {
            dataResponseFlow
                .takeWhile { raw ->
                    collected.add(raw)
                    val msg = decode<DataChunkMsg>(raw)
                    !msg.eof
                }
                .collect {}
        }

        connection.write(
            CHR_DATA_REQUEST_UUID,
            encode(DataRequestMsg(kind = kind, from = fromMs, to = toMs, limit = limit)),
        )
        collector.await()
        chunkedDecode(collected)
    }

    // --- Blob push (firmware / model) --------------------------------------

    /**
     * Push a new firmware binary. Emits progress events; terminal events
     * are Success or Failure. The flow lives as long as the transfer; the
     * UI consumes it with collect.
     */
    fun pushFirmware(bytes: ByteArray, version: String): Flow<BlobProgress> =
        pushBlob(bytes, BlobKind.FIRMWARE, version)

    /** Push a new model file (LSTM .tflite or RF .pkl). */
    fun pushModel(bytes: ByteArray, kind: String): Flow<BlobProgress> =
        pushBlob(bytes, kind, version = null)

    private fun pushBlob(
        bytes: ByteArray, kind: String, version: String?,
    ): Flow<BlobProgress> = flow {
        coroutineScope {
            emit(BlobProgress.Starting)
            val sha = sha256(bytes)
            val startMsg = BlobStartMsg(
                kind = kind,
                size = bytes.size.toLong(),
                sha256 = sha,
                version = version,
            )

            // Listen for the ready/err reply before we write -- otherwise the
            // server's notify can race past us.
            val readyResult = async {
                blobControlFlow
                    .map { decode<BlobControlEnvelope>(it) }
                    .first { it.op == Op.BLOB_READY || it.op == Op.BLOB_ERR }
            }
            connection.write(CHR_BLOB_CONTROL_UUID, encode(startMsg))
            emit(BlobProgress.WaitingForPsm)

            val ready = readyResult.await()
            if (ready.op == Op.BLOB_ERR) {
                emit(BlobProgress.Failure(ready.msg ?: "server rejected blob"))
                return@coroutineScope
            }
            val psm = ready.psm
                ?: run {
                    emit(BlobProgress.Failure("server ready notify missing psm field"))
                    return@coroutineScope
                }

            // Same pattern for the terminal OK/ERR notify.
            val finalResult = async {
                blobControlFlow
                    .map { decode<BlobControlEnvelope>(it) }
                    .first { it.op == Op.BLOB_OK || it.op == Op.BLOB_ERR }
            }

            val l2cap = connection.openL2cap(psm)
            try {
                var sent = 0L
                val total = bytes.size.toLong()
                while (sent < total) {
                    val end = (sent + L2CAP_CHUNK_BYTES).coerceAtMost(total)
                    val slice = bytes.copyOfRange(sent.toInt(), end.toInt())
                    l2cap.send(slice)
                    sent = end
                    emit(BlobProgress.Transferring(sent, total))
                }
            } finally {
                l2cap.close()
            }
            emit(BlobProgress.Verifying)

            val final = finalResult.await()
            if (final.op == Op.BLOB_OK) {
                emit(BlobProgress.Success)
            } else {
                emit(BlobProgress.Failure(final.msg ?: "blob apply failed"))
            }
        }
    }

    suspend fun disconnect() {
        connection.disconnect()
    }
}
