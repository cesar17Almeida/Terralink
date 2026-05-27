package com.astralink.terralink.ble.protocol

// Op codes used in the `op` field of CBOR control messages.
// Short ASCII strings to minimize wire overhead. Keep in sync with
// savia/ble/protocol.py::Op.
object Op {
    // time_sync write
    const val SET_TIME = "set"

    // weather write
    const val UPDATE_WEATHER = "upd"

    // data_request write
    const val GET = "get"
    const val COUNT = "count"

    // data_response notify chunks
    const val CHUNK = "chunk"

    // blob_control (client -> server)
    const val BLOB_START = "start"
    const val BLOB_ABORT = "abort"

    // blob_control (server -> client)
    const val BLOB_READY = "ready"
    const val BLOB_OK    = "ok"
    const val BLOB_ERR   = "err"
}
