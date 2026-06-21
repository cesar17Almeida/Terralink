package com.astralink.terralink.ble.protocol

// Op codes used in the `op` field of CBOR control messages.
// Short ASCII strings to minimize wire overhead. Keep in sync with
// savia/ble/protocol.py::Op.
object Op {
    // time_sync write
    const val SET_TIME = "set"

    // data_request write
    const val GET = "get"
    const val COUNT = "count"
    const val CLEAR = "clear"            // dev: wipe stored data
    const val MOCK = "mock"              // dev: inject mock data (kind=hs10|hs30|ta reading, or pred forecast)
    const val INGEST = "ingest"          // upsert timestamped points: data:[{ts_ms,kind,value,depth_cm?}]

    // data_response notify chunks
    const val CHUNK = "chunk"
    const val INGEST_OK = "ingest_ok"    // ingest ack {created, updated}

    // blob_control (client -> server)
    const val BLOB_START = "start"
    const val BLOB_ABORT = "abort"

    // blob_control (server -> client)
    const val BLOB_READY = "ready"
    const val BLOB_OK    = "ok"
    const val BLOB_ERR   = "err"

    // config write (client -> server): patch uses the same "set" verb as time_sync
    const val SET_CONFIG = "set"
    // config notify (server -> client): ack of a patch
    const val CONFIG_OK  = "config_ok"
    const val CONFIG_ERR = "config_err"

    // auth (challenge-response on CHR_AUTH_UUID)
    const val SETPW = "setpw"   // first-time password
    const val AUTH  = "auth"    // prove knowledge
    const val CHGPW = "chgpw"   // change password (needs old proof)
}
