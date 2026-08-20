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
    const val PINMAP = "pinmap" // GPIO inventory: too big (~1.1 KB) for a GATT read.
}

// Reading-kind tokens carried in a Reading/IngestPoint `kind` field. Mirrors the
// firmware savia_reading_kind_t enum (kind_str/kind_from_str).
object ReadingKind {
    const val SOIL_MOISTURE = "soil_moisture"       // VWC 0..1
    const val SOIL_TEMPERATURE = "soil_temperature" // degrees C
    const val AIR_TEMPERATURE = "air_temperature"   // TA, degrees C (LSTM input)
    const val AIR_HUMIDITY = "air_humidity"         // %RH (DHT11)
    const val DISTANCE = "distance"                 // mm (HC-SR04)
    const val GENERIC = "generic"                   // free unit (carried in the sensor's `unit`)
}

// Sensor-slot type tokens (config `type` field). Mirrors the firmware
// savia_sensor_type_t enum (sensor_type_str/sensor_type_from_str).
object SensorType {
    const val SDI12_AQUACHECK = "sdi12_aquacheck"
    const val SDI12_GENERIC = "sdi12_generic"
    const val ANALOG_LINEAR = "analog_linear"
    const val ONEWIRE_DS18B20 = "onewire_ds18b20"
    const val DHT11 = "dht11"                        // 1 digital pin -> air_temperature + air_humidity
    const val HC_SR04 = "hc_sr04"                    // 2 pins (gpio=trigger, gpio2=echo) -> distance
    const val ACTUATOR = "actuator"                  // 1 digital output; no readings, manual ON/OFF
}

// Model tokens that flag a prediction row as a scheduled irrigation event marker
// (model="sched", kind="irrigation_event") rather than a forecast point.
const val PRED_MODEL_SCHED = "sched"
const val PRED_KIND_IRRIGATION_EVENT = "irrigation_event"
