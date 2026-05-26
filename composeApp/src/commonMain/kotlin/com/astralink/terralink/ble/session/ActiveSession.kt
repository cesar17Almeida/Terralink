package com.astralink.terralink.ble.session

import com.astralink.terralink.ble.SaviaConnection
import com.astralink.terralink.ble.codec.SaviaCbor
import com.astralink.terralink.ble.codec.chunkedDecode
import com.astralink.terralink.ble.codec.decode
import com.astralink.terralink.ble.codec.encode
import com.astralink.terralink.ble.protocol.Aggregation
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
import com.astralink.terralink.ble.protocol.DataKind
import com.astralink.terralink.ble.protocol.DataRequestMsg
import com.astralink.terralink.ble.protocol.Op
import com.astralink.terralink.ble.protocol.Prediction
import com.astralink.terralink.ble.protocol.Reading
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
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray

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
     * Ask the Pi for raw sensor readings inside [fromMs, toMs).
     * Both bounds are optional; with neither, returns everything in the
     * retention window (capped by `limit` if set).
     */
    suspend fun requestRawReadings(
        fromMs: Long? = null,
        toMs: Long? = null,
        limit: Int? = null,
    ): List<Reading> = decodePayload(requestData(DataKind.RAW, fromMs, toMs, limit))

    /** Hourly aggregations derived on the Pi from the readings table. */
    suspend fun requestHourlyAggregations(
        fromMs: Long? = null,
        toMs: Long? = null,
        limit: Int? = null,
    ): List<Aggregation> = decodePayload(requestData(DataKind.AGG, fromMs, toMs, limit))

    /** Model outputs. Returns [] until the ML pipeline lands on the Pi. */
    suspend fun requestPredictions(
        fromMs: Long? = null,
        toMs: Long? = null,
        limit: Int? = null,
    ): List<Prediction> = decodePayload(requestData(DataKind.PRED, fromMs, toMs, limit))

    /**
     * Lower-level helper used by the typed `request*` methods. Issues a
     * data_request and collects data_response chunks until eof, returning
     * the reassembled CBOR bytes. Public so callers can do their own
     * decoding for kinds we haven't typed yet.
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

    @OptIn(ExperimentalSerializationApi::class)
    private inline fun <reified T> decodePayload(bytes: ByteArray): List<T> =
        SaviaCbor.decodeFromByteArray<List<T>>(bytes)

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
