package com.astralink.terralink.ble.protocol

// Mirror of savia/ble/protocol.py. Keep both files in sync.

const val PROTOCOL_VERSION: Int = 1

const val SAVIA_SERVICE_UUID: String = "5a71a000-0000-0000-0000-000000000001"

const val CHR_STATUS_UUID: String        = "5a71a000-0000-0000-0000-000000000010"
const val CHR_TIME_SYNC_UUID: String     = "5a71a000-0000-0000-0000-000000000011"
const val CHR_WEATHER_UUID: String       = "5a71a000-0000-0000-0000-000000000012"  // legacy: TA now via ingest
const val CHR_CONFIG_UUID: String        = "5a71a000-0000-0000-0000-000000000013"
const val CHR_AUTH_UUID: String          = "5a71a000-0000-0000-0000-000000000014"
const val CHR_PINMAP_UUID: String        = "5a71a000-0000-0000-0000-000000000015"  // GPIO inventory (read)

const val CHR_DATA_REQUEST_UUID: String  = "5a71a000-0000-0000-0000-000000000020"
const val CHR_DATA_RESPONSE_UUID: String = "5a71a000-0000-0000-0000-000000000021"

const val CHR_BLOB_CONTROL_UUID: String  = "5a71a000-0000-0000-0000-000000000030"

// L2CAP CoC PSM range. The server picks the first free one per transfer.
val PSM_RANGE: IntRange = 0x0080..0x00FF

// Negotiated ATT MTU target on the Pi side.
const val ATT_MTU_TARGET: Int = 247

// CBOR data_response `p` field max bytes per notify.
const val DATA_CHUNK_BYTES: Int = 200

// Hard ceiling for a single GATT control write/notify payload (the firmware's
// RX buffer). Outbound encodes are bounded by this.
const val MAX_CONTROL_MSG_BYTES: Int = 512

// Inbound GATT READS (status / config / pinmap) can be larger than a single
// control write: the platform reassembles them via ATT long reads. The pinmap
// alone is ~760 B. Bound the decode path here instead of the write ceiling.
const val MAX_READ_MSG_BYTES: Int = 4096

// Max single-transfer blob size. Anything larger is refused.
const val MAX_BLOB_BYTES: Long = 256L * 1024 * 1024  // 256 MB
