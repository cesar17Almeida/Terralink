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

// --- data_request (write to CHR_DATA_REQUEST_UUID) ---------------------------
// (The legacy `weather` characteristic is no longer used by the app -- the air
// temperature that feeds the LSTM is pushed as timestamped points via `ingest`.)

@Serializable
data class DataRequestMsg(
    val v: Int = PROTOCOL_VERSION,
    val op: String = Op.GET,
    val kind: String,            // one of DataKind.*
    val from: Long? = null,      // epoch ms inclusive
    val to: Long? = null,        // epoch ms exclusive
    val limit: Int? = null,
)

/**
 * Same characteristic + range fields as DataRequestMsg, but with op="count".
 * The Pi replies on data_response with a single chunked frame carrying
 * {count: N} so the app can show "Página N / M" before paging starts.
 */
@Serializable
data class DataCountRequestMsg(
    val v: Int = PROTOCOL_VERSION,
    val op: String = Op.COUNT,
    val kind: String,
    val from: Long? = null,
    val to: Long? = null,
)

@Serializable
data class CountMsg(val count: Long)

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

// --- config (read / write / notify on CHR_CONFIG_UUID) -----------------------
// Only the config app (TerraLink) uses this; the client dashboard ignores it.

/** "What device is this?" card (static identity; liveness is in StatusMsg). */
@Serializable
data class DeviceInfo(
    val model: String,                       // e.g. "Raspberry Pi Pico WH"
    val mcu: String,                         // e.g. "RP2040"
    val img: String,                         // asset key for a bundled PNG, e.g. "pico_wh"
    val fw: String,
)

/** One configured sensor slot. */
@Serializable
data class SensorInfo(
    val port: Int,
    val gpio: Int,
    val type: String,                        // e.g. "sdi12_aquacheck"
    val addr: String,
)

/** Used / free GPIO partition for the pin map. */
@Serializable
data class GpioMap(
    val used: List<Int> = emptyList(),
    val free: List<Int> = emptyList(),
)

/** config READ: full snapshot (no `op` -- it's a plain GATT read). */
@Serializable
data class ConfigSnapshotMsg(
    val v: Int = PROTOCOL_VERSION,
    val device: DeviceInfo,
    val name: String = "Savia",                          // advertised BLE name (app-editable)
    @SerialName("sleep_s") val sleepS: Int,
    @SerialName("deep_sleep") val deepSleep: Boolean,
    @SerialName("capture_s") val captureS: Int = 3600,   // capture cadence (s)
    @SerialName("daily_hour") val dailyHour: Int = 20,   // UTC hour of the daily cycle
    @SerialName("mock") val mockEnabled: Boolean = true, // dev: mock data generator
    @SerialName("log_level") val logLevel: Int = 1,      // 0=debug, 1=info
    @SerialName("wake_gpio") val wakeGpio: Int,
    val sensors: List<SensorInfo> = emptyList(),
    val gpio: GpioMap = GpioMap(),                        // reserved for the future pin map
)

/** config WRITE: patch with only the fields being changed. */
@Serializable
data class ConfigPatchMsg(
    val v: Int = PROTOCOL_VERSION,
    val op: String = Op.SET_CONFIG,
    val name: String? = null,                            // rename the BLE advertisement
    @SerialName("sleep_s") val sleepS: Int? = null,
    @SerialName("deep_sleep") val deepSleep: Boolean? = null,
    @SerialName("capture_s") val captureS: Int? = null,
    @SerialName("daily_hour") val dailyHour: Int? = null,
    @SerialName("mock") val mock: Boolean? = null,
    @SerialName("log_level") val logLevel: Int? = null,
)

/** data_request: wipe stored data (dev). Replies with {count:0} on data_response. */
@Serializable
data class ClearRequestMsg(
    val v: Int = PROTOCOL_VERSION,
    val op: String = Op.CLEAR,
    val kind: String = "raw",
)

/**
 * data_request: inject mock data (dev). kind = "hs10" | "hs30" | "ta" injects one
 * reading; kind = "pred" publishes a synthetic 24 h HS30 forecast on `pred`.
 */
@Serializable
data class MockRequestMsg(
    val v: Int = PROTOCOL_VERSION,
    val op: String = Op.MOCK,
    val kind: String,
)

// --- ingest (write to CHR_DATA_REQUEST_UUID) ---------------------------------
// Upsert one or more timestamped points by (ts_ms, port, kind, depth_cm): if a
// point already exists its value is overwritten, otherwise it is appended. This
// is how the app pushes the TA forecast (kind=air_temperature, future ts) and the
// recent measured TA (past ts) that feed the LSTM -- one point at a time or a
// batch. depthCm/port carry concrete defaults so the wire never holds a CBOR null
// (the firmware tolerates null, but sending an int keeps the contract simple).

/** One timestamped point to upsert. */
@Serializable
data class IngestPoint(
    @SerialName("ts_ms") val tsMs: Long,     // epoch UTC ms (past = measured, future = forecast)
    val kind: String,                        // ReadingKind.*
    val value: Double,                       // VWC 0..1 or degrees C
    @SerialName("depth_cm") val depthCm: Int = 0,   // 0 for air; 10/30/... for soil
    val port: Int = 1,
)

/** ingest WRITE: a batch of points to create-or-update. */
@Serializable
data class IngestMsg(
    val v: Int = PROTOCOL_VERSION,
    val op: String = Op.INGEST,
    val data: List<IngestPoint>,
)

/** data_response: ingest ack reporting how many points were created vs updated. */
@Serializable
data class IngestAckMsg(
    val v: Int = PROTOCOL_VERSION,
    val op: String = Op.INGEST_OK,
    val created: Int = 0,
    val updated: Int = 0,
)

// --- auth (read / write on CHR_AUTH_UUID) -----------------------------------
// Byte-string fields MUST carry @ByteString (CBOR major 2); without it kotlinx
// encodes ByteArray as an int array and the firmware rejects it.

/** auth READ: {v, prov, authed, nonce}. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AuthStateMsg(
    val v: Int = PROTOCOL_VERSION,
    val prov: Boolean,                 // a password is set
    val authed: Boolean,               // this connection is authenticated
    @ByteString val nonce: ByteArray,  // challenge for the next proof
)

/** auth WRITE: first-time password (only when unprovisioned). key = SHA256(password). */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AuthSetMsg(
    val v: Int = PROTOCOL_VERSION,
    val op: String = Op.SETPW,
    @ByteString val key: ByteArray,
)

/** auth WRITE: prove knowledge. mac = SHA256(key || nonce). */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AuthMsg(
    val v: Int = PROTOCOL_VERSION,
    val op: String = Op.AUTH,
    @ByteString val mac: ByteArray,
)

/** auth WRITE: change password. old_mac proves the current one; key = SHA256(new). */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AuthChgMsg(
    val v: Int = PROTOCOL_VERSION,
    val op: String = Op.CHGPW,
    @SerialName("old_mac") @ByteString val oldMac: ByteArray,
    @ByteString val key: ByteArray,
)

/** config NOTIFY: ack of a patch (config_ok with the applied values, or config_err + msg). */
@Serializable
data class ConfigAckMsg(
    val v: Int = PROTOCOL_VERSION,
    val op: String,
    @SerialName("sleep_s") val sleepS: Int? = null,
    @SerialName("deep_sleep") val deepSleep: Boolean? = null,
    val msg: String? = null,
)
