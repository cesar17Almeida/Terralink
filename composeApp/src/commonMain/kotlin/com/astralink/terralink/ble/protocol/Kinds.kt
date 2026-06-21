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
    const val LOGS = "logs" // Recent firmware log lines (array of text).
}

// Reading-kind tokens carried in a Reading/IngestPoint `kind` field. Mirrors the
// firmware savia_reading_kind_t enum (kind_str/kind_from_str).
object ReadingKind {
    const val SOIL_MOISTURE = "soil_moisture"       // VWC 0..1
    const val SOIL_TEMPERATURE = "soil_temperature" // degrees C
    const val AIR_TEMPERATURE = "air_temperature"   // TA, degrees C (LSTM input)
}
