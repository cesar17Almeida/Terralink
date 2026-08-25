package com.astralink.terralink.timeline

import com.astralink.terralink.ble.protocol.Prediction
import com.astralink.terralink.ble.protocol.Reading
import com.astralink.terralink.ble.protocol.StatusMsg

private const val HOUR_MS = 3_600_000L

/**
 * Turn one connection's worth of station data into journal entries.
 *
 * Everything here is read from messages the firmware already serves -- status,
 * raw readings, predictions, the log ring -- and reinterpreted as moments. The
 * station never emits an "event"; it emits state, and state has timestamps in it.
 * Harvesting those is what lets the timeline exist without touching the firmware.
 */
fun harvestEvents(
    status: StatusMsg,
    readings: List<Reading>,
    predictions: List<Prediction>,
    logs: List<String>,
    forecastKind: String = "hs30_forecast",
): List<StationEvent> {
    val out = mutableListOf<StationEvent>()
    // The station's own clock, or the phone's guess of it. Every stamp below is in
    // the station's frame, so mixing in phone time here would skew the whole track.
    val stationNow = status.nowMs

    // --- captures ------------------------------------------------------------
    // One mark per (instant, port), not per reading: an AquaCheck sweep returns six
    // depths from a single wake, and six stacked marks would read as six wakes.
    readings
        .groupBy { it.tsMs to it.port }
        .forEach { (key, group) ->
            val (ts, port) = key
            val kinds = group.map { it.kind }.distinct()
            out += StationEvent(
                tsMs = ts,
                kind = EventKind.SAMPLE,
                port = port,
                detail = "Puerto $port · ${group.size} ${if (group.size == 1) "valor" else "valores"}" +
                    if (kinds.size == 1) " de ${kindWord(kinds.first())}" else "",
            )
        }

    // --- the model run -------------------------------------------------------
    // The forecast doesn't carry its own issue time; its first step is H+1, so the
    // run happened one hour before the earliest target. Same derivation the archive
    // uses, so the journal and the archive agree on when the LSTM ran.
    predictions.filter { it.kind == forecastKind }.minByOrNull { it.tsMs }?.let { first ->
        val values = predictions.filter { it.kind == forecastKind }
        out += StationEvent(
            tsMs = first.tsMs - HOUR_MS,
            kind = EventKind.LSTM,
            detail = "Pronóstico HS30 · ${values.size} h por delante" +
                (values.minByOrNull { it.value }?.let { " · mínimo ${fmt3(it.value)}" } ?: ""),
        )
    }

    // --- the "last time X" stamps in the status ------------------------------
    status.lastSyncMs?.takeIf { it > 0 }?.let {
        out += StationEvent(it, EventKind.SYNC, detail = "Reloj puesto en hora")
    }
    status.weatherUpdatedMs?.takeIf { it > 0 }?.let {
        // The weather cache only ever changes because a downlink brought hour + TA,
        // so its update stamp IS the downlink's arrival time.
        out += StationEvent(it, EventKind.LORA_DOWN, detail = "Downlink · hora y temperatura del aire")
    }
    status.lora?.let { l ->
        val last = l.lastMs?.takeIf { it > 0 } ?: return@let
        val signal = l.rssi?.let { r -> "RSSI $r dBm" + (l.snr?.let { s -> " · SNR ${fmt1(s)} dB" } ?: "") }
        out += StationEvent(
            tsMs = last,
            kind = EventKind.LORA_UP,
            ok = l.joined,
            detail = signal ?: if (l.joined) "Uplink entregado" else "Enlace sin unir a la red",
        )
    }

    // --- boot ----------------------------------------------------------------
    if (stationNow != null && status.uptimeS > 0) {
        out += StationEvent(
            tsMs = stationNow - status.uptimeS * 1_000L,
            kind = EventKind.BOOT,
            detail = "Arranque · fw ${status.fw}",
        )
    }

    // --- the log ring --------------------------------------------------------
    if (stationNow != null) out += parseLogEvents(logs, stationNow)

    return out.sortedBy { it.tsMs }
}

private fun kindWord(kind: String): String = when (kind) {
    "soil_moisture" -> "humedad de suelo"
    "soil_temperature" -> "temperatura de suelo"
    "air_temperature" -> "temperatura de aire"
    "air_humidity" -> "humedad de aire"
    "distance" -> "distancia"
    else -> kind
}

private fun fmt1(v: Double): String = fmtDecimals(v, 1)
private fun fmt3(v: Double): String = fmtDecimals(v, 3)

/** Fixed-decimals formatter; the platforms don't share a String.format. */
internal fun fmtDecimals(v: Double, places: Int): String {
    var mul = 1L
    repeat(places) { mul *= 10 }
    val neg = v < 0
    val scaled = kotlin.math.round(kotlin.math.abs(v) * mul).toLong()
    val whole = scaled / mul
    val frac = scaled % mul
    val sign = if (neg && (whole != 0L || frac != 0L)) "-" else ""
    return if (places == 0) "$sign$whole" else "$sign$whole.${frac.toString().padStart(places, '0')}"
}
