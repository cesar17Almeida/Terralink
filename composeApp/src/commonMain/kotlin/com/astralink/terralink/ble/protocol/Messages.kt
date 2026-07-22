package com.astralink.terralink.ble.protocol

import kotlinx.serialization.EncodeDefault
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

/**
 * LoRa/TTN link state inside StatusMsg. `joined` proves a TTN gateway relayed the
 * OTAA handshake both ways; rssi/snr are the DOWNLINK signal the node measured from
 * the last uplink ACK (null until first measured). lastMs is when it was seen.
 */
@Serializable
data class LoraStatus(
    val inited: Boolean = false,           // module replied to AT (RX/TX + power OK)
    val joined: Boolean = false,
    val rssi: Int? = null,                 // dBm (downlink)
    val snr: Double? = null,               // dB
    @SerialName("last_ms") val lastMs: Long? = null,
    val module: String = "",               // module AT+VER reply ("" if it stayed silent)
    val seq: Int = 0,                      // bumps each completed ping (wait for a fresh one)
)

/** One digital actuator slot's live state (StatusMsg.act[]). Parsed tolerantly:
 *  older firmware omits `act` entirely. */
@Serializable
data class ActuatorState(
    val port: Int,
    val gpio: Int? = null,
    val on: Boolean = false,
)

@Serializable
data class StatusMsg(
    val v: Int,
    val fw: String,
    val mode: String? = null,                            // inference mode: "local" | "forward"
    @SerialName("irrigation_hour") val irrigationHour: Int? = null,   // local watering hour
    @SerialName("now_ms") val nowMs: Long? = null,       // device wall clock, epoch ms (null = unsynced)
    @SerialName("utc_offset_min") val utcOffsetMin: Int? = null,      // station-configured offset
    @SerialName("uptime_s")
    val uptimeS: Long,
    @SerialName("last_sync_ms")
    val lastSyncMs: Long? = null,
    @SerialName("weather_updated_ms")
    val weatherUpdatedMs: Long? = null,
    val lora: LoraStatus? = null,
    val act: List<ActuatorState>? = null,                // configured digital actuator slots
)

/** data_request: drive a digital actuator slot ON/OFF (auth-gated, like config). The
 *  station replies on data_response; poll [StatusMsg.act] afterwards for the new state. */
@Serializable
data class ActuatorRequestMsg(
    val v: Int = PROTOCOL_VERSION,
    val op: String = Op.ACT,
    val port: Int,
    val on: Boolean,
)

/** data_request: raw SDI-12 console. `cmd` non-null queues a command on the probe wired
 *  to `gpio`; null just polls. Reply lands in [AtResultMsg] (same shape as op "at"). */
@Serializable
data class Sdi12RequestMsg(
    val v: Int = PROTOCOL_VERSION,
    val op: String = Op.SDI12,
    val cmd: String? = null,
    val gpio: Int,
)

/** data_request: trigger an on-demand LoRa ping (join + confirmed uplink). The
 *  result lands in StatusMsg.lora -- poll readStatus() after sending this. */
@Serializable
data class LoraPingRequestMsg(
    val v: Int = PROTOCOL_VERSION,
    val op: String = Op.LORA,
)

/** data_request: raw AT terminal. `cmd` non-null queues a command on the module;
 *  null just polls. The reply lands in [AtResultMsg] (read it back via the same op). */
@Serializable
data class AtRequestMsg(
    val v: Int = PROTOCOL_VERSION,
    val op: String = Op.AT,
    val cmd: String? = null,
)

/** data_response for op "at": the last AT exchange the station ran. */
@Serializable
data class AtResultMsg(
    val seq: Int = 0,
    val cmd: String = "",
    val lines: List<String> = emptyList(),
)

// --- config (read / write / notify on CHR_CONFIG_UUID) -----------------------
// Only the config app (TerraLink) uses this; the client dashboard ignores it.

/** "What device is this?" card (static identity; liveness is in StatusMsg). */
@Serializable
data class DeviceInfo(
    val model: String,                       // e.g. "Raspberry Pi Pico WH"
    val mcu: String,                         // e.g. "RP2040"
    val fw: String,
)

/** One installer-labelled channel of a generic SDI-12 sensor. */
@Serializable
data class ChannelInfo(
    val kind: String,                        // "soil_moisture" | "soil_temperature" | "air_temperature"
    @SerialName("depth_cm") val depthCm: Int = 0,
)

/**
 * One configured sensor slot, as the firmware serialises it (…0013). The base
 * fields are always present; the decoding fields appear per type (analog ->
 * kind/depth/scale/offset, 1-Wire -> kind/depth, SDI-12 generic -> chan[]). The
 * extra fields let the wizard re-open a sensor for editing without losing them.
 */
@Serializable
data class SensorInfo(
    val port: Int,
    val gpio: Int,
    val type: String,                        // e.g. "sdi12_aquacheck"
    val addr: String,
    @SerialName("interval_s") val intervalS: Int = 0,   // per-sensor cadence (s); 0 = follow capture_s
    val kind: String? = null,                // analog / 1-Wire single-value kind
    @SerialName("depth_cm") val depthCm: Int? = null,
    val scale: Double? = null,               // analog_linear: value = scale*raw + offset
    val offset: Double? = null,
    val chan: List<ChannelInfo>? = null,     // sdi12_generic: installer-labelled channels
    @SerialName("gpio2") val gpio2: Int? = null,   // second pin (HC-SR04 echo); absent = single-pin
    val unit: String? = null,                // free-text unit label (generic sensors); <= 8 chars
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
    @SerialName("lora_period_s") val loraPeriodS: Int = 3600,     // LoRa uplink cadence (s)
    @SerialName("inference_mode") val inferenceMode: String = "forward", // "local" | "forward"
    @SerialName("infer_dev") val inferDev: Boolean = false,       // build supports on-device inference (RO)
    @SerialName("utc_offset_min") val utcOffsetMin: Int = 0,      // station's local UTC offset (may be negative)
    @SerialName("irrigation_hour") val irrigationHour: Int = 6,   // local hour the scheduler waters
    val lat: Double? = null,                             // station coordinates (null = unset)
    val lon: Double? = null,
    val sensors: List<SensorInfo> = emptyList(),
    val gpio: GpioMap = GpioMap(),                        // reserved for the future pin map
)

/** One channel for a generic SDI-12 sensor patch (omitted-null = not set). */
@Serializable
data class ChannelPatch(
    val kind: String,
    @SerialName("depth_cm") val depthCm: Int,
)

/**
 * One sensor in a config-patch `sensors[]` table. `gpio`/`type` are always sent;
 * the rest are per-type and omitted when null (sparse). Mirrors the firmware's
 * parse_sensor_slot: addr (SDI-12), interval_s (cadence, 0/omitted = global),
 * kind/depth_cm + scale/offset (analog), kind/depth_cm (1-Wire), chan[] (generic).
 */
@Serializable
data class SensorPatch(
    val gpio: Int,
    val type: String,
    val addr: String? = null,
    @SerialName("interval_s") val intervalS: Int? = null,
    val kind: String? = null,
    @SerialName("depth_cm") val depthCm: Int? = null,
    val scale: Double? = null,
    val offset: Double? = null,
    val chan: List<ChannelPatch>? = null,
    @SerialName("gpio2") val gpio2: Int? = null,   // second pin (HC-SR04 echo); null = single-pin
    val unit: String? = null,                      // free-text unit label; null omitted
)

/**
 * config WRITE: a SPARSE patch -- only the fields being changed are non-null.
 * Encoded with [encodeSparse] so unchanged (null) fields are omitted from the
 * wire (a name-only save must NOT carry `deep_sleep` etc.). `v`/`op` are kept
 * via @EncodeDefault(ALWAYS) since the firmware requires them.
 *
 * `sensors` is a FULL replacement of the sensor table (the firmware swaps the
 * whole array): send the complete desired list, or null to leave it untouched.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ConfigPatchMsg(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val v: Int = PROTOCOL_VERSION,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val op: String = Op.SET_CONFIG,
    val name: String? = null,                            // rename the BLE advertisement
    @SerialName("sleep_s") val sleepS: Int? = null,
    @SerialName("deep_sleep") val deepSleep: Boolean? = null,
    @SerialName("capture_s") val captureS: Int? = null,
    @SerialName("daily_hour") val dailyHour: Int? = null,
    @SerialName("mock") val mock: Boolean? = null,
    @SerialName("log_level") val logLevel: Int? = null,
    @SerialName("lora_period_s") val loraPeriodS: Int? = null,
    @SerialName("inference_mode") val inferenceMode: String? = null,   // "local" | "forward"
    @SerialName("utc_offset_min") val utcOffsetMin: Int? = null,       // may be negative
    @SerialName("irrigation_hour") val irrigationHour: Int? = null,
    // Coords: send lat + lon TOGETHER as numbers to set. Both stay null here (and are
    // omitted by the sparse encoder) when unchanged -- CLEARING requires explicit CBOR
    // nulls, which the sparse encoder can't emit, so use [ConfigClearCoordsMsg] instead.
    val lat: Double? = null,
    val lon: Double? = null,
    val sensors: List<SensorPatch>? = null,
)

/**
 * config WRITE: clear the station's stored coordinates. The firmware distinguishes an
 * ABSENT `lat`/`lon` key (leave untouched) from a PRESENT CBOR null (clear), but the
 * sparse encoder drops nulls -- so this dedicated message forces `lat`/`lon` onto the
 * wire as explicit nulls via @EncodeDefault(ALWAYS). Encode it with [encodeSparse].
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ConfigClearCoordsMsg(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val v: Int = PROTOCOL_VERSION,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val op: String = Op.SET_CONFIG,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val lat: Double? = null,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val lon: Double? = null,
)

/** One pin of the GPIO inventory (…0015). */
@Serializable
data class PinEntry(
    val gpio: Int,
    val state: String,                       // "free" | "in_use" | "reserved"
    val reason: String = "",                 // "sensor" | "wireless" | "wake_btn" | "lora_uart" | ""
    val caps: Int = 0,                       // savia_pin_cap_t bitmask (matches PinCap.*)
    val port: Int? = null,                   // 1..6 when a sensor occupies it
)

/** pinmap READ (…0015): used to offer only free + capable pins in the wizard. */
@Serializable
data class PinmapMsg(
    val v: Int = PROTOCOL_VERSION,
    val pins: List<PinEntry> = emptyList(),
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
