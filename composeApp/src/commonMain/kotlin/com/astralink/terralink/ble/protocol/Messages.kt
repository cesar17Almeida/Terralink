package com.astralink.terralink.ble.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.ByteString

// CBOR control messages. kotlinx-serialization-cbor encodes/decodes by
// field name; @SerialName overrides the wire name to keep snake_case
// alignment with the Python side without forcing snake_case in Kotlin.
//
// Wire layout for every control message: { v, op, ... }
//   v  -> PROTOCOL_VERSION
//   op -> one of the Op constants
//   ... -> per-op fields below

// --- time_sync (write to CHR_TIME_SYNC_UUID) ---------------------------------

@Serializable
data class TimeSyncMsg(
    val v: Int = PROTOCOL_VERSION,
    val op: String = Op.SET_TIME,
    val ms: Long,
)

// --- weather (write to CHR_WEATHER_UUID) -------------------------------------

@Serializable
data class WeatherData(
    val temp: Double? = null,
    val humidity: Double? = null,
    @SerialName("rad_solar")
    val radSolar: Double? = null,
    val eto: Double? = null,
    val date: String? = null,
)

@Serializable
data class WeatherMsg(
    val v: Int = PROTOCOL_VERSION,
    val op: String = Op.UPDATE_WEATHER,
    val data: WeatherData,
)

// --- data_request (write to CHR_DATA_REQUEST_UUID) ---------------------------

@Serializable
data class DataRequestMsg(
    val v: Int = PROTOCOL_VERSION,
    val op: String = Op.GET,
    val kind: String,            // one of DataKind.*
    val from: Long? = null,      // epoch ms inclusive
    val to: Long? = null,        // epoch ms exclusive
    val limit: Int? = null,
)

// --- data_response (notify chunks on CHR_DATA_RESPONSE_UUID) ----------------

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class DataChunkMsg(
    val v: Int = PROTOCOL_VERSION,
    val op: String = Op.CHUNK,
    val s: Int,         // sequence number, starts at 0
    val t: Int,         // total chunk count
    val eof: Boolean,
    // @ByteString -> CBOR major type 2 (byte string), matching how Python's
    // cbor2 expects bytes. Without it, ByteArray serializes as an int array.
    @ByteString
    val p: ByteArray,   // payload slice
) {
    // ByteArray needs manual equals/hashCode for value semantics.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DataChunkMsg) return false
        return v == other.v && op == other.op && s == other.s && t == other.t &&
                eof == other.eof && p.contentEquals(other.p)
    }
    override fun hashCode(): Int =
        ((((v * 31 + op.hashCode()) * 31 + s) * 31 + t) * 31 + eof.hashCode()) * 31 + p.contentHashCode()
}

// --- blob_control (write & notify on CHR_BLOB_CONTROL_UUID) ------------------

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class BlobStartMsg(
    val v: Int = PROTOCOL_VERSION,
    val op: String = Op.BLOB_START,
    val kind: String,            // BlobKind.*
    val size: Long,              // bytes; > 0 and <= MAX_BLOB_BYTES
    @ByteString
    val sha256: ByteArray,       // exactly 32 bytes
    val version: String? = null, // required for kind=FIRMWARE
    @ByteString
    val sig: ByteArray? = null,  // optional Ed25519 signature
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BlobStartMsg) return false
        return v == other.v && op == other.op && kind == other.kind &&
                size == other.size && sha256.contentEquals(other.sha256) &&
                version == other.version &&
                (sig?.contentEquals(other.sig ?: ByteArray(0)) ?: (other.sig == null))
    }
    override fun hashCode(): Int {
        var h = v
        h = h * 31 + op.hashCode()
        h = h * 31 + kind.hashCode()
        h = h * 31 + size.hashCode()
        h = h * 31 + sha256.contentHashCode()
        h = h * 31 + (version?.hashCode() ?: 0)
        h = h * 31 + (sig?.contentHashCode() ?: 0)
        return h
    }
}

@Serializable
data class BlobAbortMsg(
    val v: Int = PROTOCOL_VERSION,
    val op: String = Op.BLOB_ABORT,
)

@Serializable
data class BlobReadyMsg(
    val v: Int = PROTOCOL_VERSION,
    val op: String = Op.BLOB_READY,
    val psm: Int,
)

@Serializable
data class BlobOkMsg(
    val v: Int = PROTOCOL_VERSION,
    val op: String = Op.BLOB_OK,
)

@Serializable
data class BlobErrMsg(
    val v: Int = PROTOCOL_VERSION,
    val op: String = Op.BLOB_ERR,
    val msg: String,
)

/**
 * Generic envelope for inbound blob_control notifications. Used by the
 * session layer to dispatch on `op` without knowing the concrete subtype
 * ahead of time. Fields are optional because each op only sets a subset.
 */
@Serializable
data class BlobControlEnvelope(
    val v: Int,
    val op: String,
    val psm: Int? = null,
    val msg: String? = null,
)

// --- status (read or notify on CHR_STATUS_UUID) ------------------------------

@Serializable
data class StatusMsg(
    val v: Int,
    val fw: String,
    @SerialName("uptime_s")
    val uptimeS: Long,
    @SerialName("last_sync_ms")
    val lastSyncMs: Long? = null,
    @SerialName("weather_updated_ms")
    val weatherUpdatedMs: Long? = null,
)
