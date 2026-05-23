package com.astralink.terralink.ble.protocol

// Kinds for blob_control transfers. Mirrors savia.ble.protocol.BlobKind.
object BlobKind {
    const val FIRMWARE = "fw"
    const val LSTM = "lstm"
    const val RF = "rf"
}

// Kinds for data_request queries. Mirrors savia.ble.protocol.DataKind.
object DataKind {
    const val RAW = "raw"   // Individual sensor readings.
    const val AGG = "agg"   // Hourly aggregations.
    const val PRED = "pred" // Model predictions / outputs.
}
