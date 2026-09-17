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
    val out = mutableListOf<StationEvent>()      // derived from state
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
        // last_ms is stamped when the module reports an RSSI/SNR, and it only does
        // that for a frame it RECEIVED (an ACK or a data downlink) in the RX window
        // after an uplink. One stamp is therefore evidence of both directions --
        // the uplink got through and something came back down -- so it marks both
        // lanes, and it stays evidence even if the link has since dropped.
        val last = l.lastMs?.takeIf { it > 0 } ?: return@let
        val signal = l.rssi?.let { r -> "RSSI $r dBm" + (l.snr?.let { s -> " · SNR ${fmt1(s)} dB" } ?: "") }
        out += StationEvent(last, EventKind.LORA_UP, detail = "Uplink entregado · el gateway respondió")
        out += StationEvent(last, EventKind.LORA_DOWN,
            detail = "Recepción del gateway" + (signal?.let { " · $it" } ?: ""))
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
    val logged = if (stationNow != null) parseLogEvents(logs, stationNow) else emptyList()

    // The status and the log describe the same moment at different instants (ms at
    // the start of a radio cycle vs whole seconds later on). One mark per event:
    // stamped at the earliest report, which the status keeps after the log ring has
    // rotated, and worded by the log, which says what actually happened.
    val tagged = out.map { it to false } + logged.map { it to true }
    return tagged
        .groupBy { (e, _) -> e.kind to e.port }
        .flatMap { (key, marks) ->
            clusterWithin(marks.sortedBy { it.first.tsMs }, key.first.sameEventWindowMs()) { it.first.tsMs }
                .map { run ->
                    val words = run.filter { it.second }.ifEmpty { run }.maxBy { it.first.detail.length }.first
                    words.copy(tsMs = run.first().first.tsMs, ok = run.all { it.first.ok })
                }
        }
        .sortedBy { it.tsMs }
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
