package com.astralink.terralink.ble.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Shape of the records that travel inside a `data_response` payload.
 * The Pi serializes a CBOR array of these for each DataKind. Keep in
 * sync with savia/ble/handlers.py serialize_readings/aggregations.
 */

// data_request kind = "raw"
@Serializable
data class Reading(
    @SerialName("ts_ms")
    val tsMs: Long,           // epoch UTC ms
    val port: Int,            // 1..6 (physical port on the station)
    val kind: String,         // "soil_moisture" | "temperature" | "ec" | ...
    val value: Double,
    @SerialName("depth_cm")
    val depthCm: Int? = null, // null for sensors without a depth
)

// data_request kind = "agg" -- hourly aggregation derived from `readings`
@Serializable
data class Aggregation(
    @SerialName("hour_ms")
    val hourMs: Long,         // epoch UTC ms of HH:00:00 (hour start)
    val port: Int,
    val kind: String,
    val count: Int,
    val mean: Double,
    val min: Double,
    val max: Double,
    @SerialName("depth_cm")
    val depthCm: Int? = null,
)

// data_request kind = "pred" -- model output. Shape fixed; Pi returns []
// until the ML pipeline starts emitting predictions.
@Serializable
data class Prediction(
    @SerialName("ts_ms")
    val tsMs: Long,           // moment the prediction applies to
    val model: String,        // "lstm-hs30" | "rf-yield" | ...
    val port: Int? = null,    // null for global predictions
    val kind: String,         // typically "soil_moisture"
    val value: Double,
    val confidence: Double? = null,
)
