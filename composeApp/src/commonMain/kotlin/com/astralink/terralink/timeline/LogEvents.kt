package com.astralink.terralink.timeline

private const val MS_PER_DAY = 86_400_000L

/**
 * Recover past events from the firmware's log ring (`data_request kind="logs"`).
 *
 * The ring is 24 lines of at most 80 chars, prefixed `HH:MM:SS ` once the wall
 * clock is synced (`src/system/log.c`), and it is the only place the station says
 * out loud that an uplink left, a downlink came back or the LSTM ran -- its status
 * message only carries "the last time" for a couple of those. Parsing it costs
 * nothing on the station and needs no firmware change; the journal then keeps what
 * we read before the ring rotates it away.
 *
 * Lines with a `+Ns ` prefix (clock not yet synced) are skipped: an event we can't
 * place in time is worse than no event on a timeline.
 */
fun parseLogEvents(lines: List<String>, stationNowMs: Long): List<StationEvent> =
    lines.mapNotNull { line ->
        val stamped = parseStamp(line, stationNowMs) ?: return@mapNotNull null
        val (tsMs, body) = stamped
        classify(body)?.let { (kind, ok, detail) ->
            StationEvent(tsMs = tsMs, kind = kind, ok = ok, detail = detail)
        }
    }

/**
 * `HH:MM:SS rest` -> (epoch ms, rest). The stamp carries no date, so it resolves
 * to the most recent instant with that UTC time of day at or before the station's
 * own clock -- the ring only ever holds the last minutes of its life, so the
 * wrap-back is right whenever the line is from "today or just before midnight".
 */
private fun parseStamp(line: String, stationNowMs: Long): Pair<Long, String>? {
    if (line.length < 9) return null
    if (line[2] != ':' || line[5] != ':' || line[8] != ' ') return null
    val h = line.substring(0, 2).toIntOrNull() ?: return null
    val m = line.substring(3, 5).toIntOrNull() ?: return null
    val s = line.substring(6, 8).toIntOrNull() ?: return null
    if (h > 23 || m > 59 || s > 59) return null
    val ofDay = ((h * 60L + m) * 60L + s) * 1000L
    val dayStart = stationNowMs - floorMod(stationNowMs, MS_PER_DAY)
    var ts = dayStart + ofDay
    if (ts > stationNowMs) ts -= MS_PER_DAY      // stamped before midnight
    return ts to line.substring(9)
}

private fun floorMod(a: Long, b: Long): Long {
    val r = a % b
    return if (r < 0) r + b else r
}

/** The INFO/WARN lines worth a mark, in the words the firmware prints them. */
private fun classify(body: String): Triple<EventKind, Boolean, String>? = when {
    body.startsWith("LoRa: uplink sent") ->
        Triple(EventKind.LORA_UP, true, "Uplink entregado · sin downlink en la ventana RX")
    body.startsWith("LoRa: cycle due") ->
        Triple(EventKind.LORA_UP, true, "Ciclo LoRa · " + body.removePrefix("LoRa: cycle due ").trim('(', ')'))
    body.startsWith("LoRa: uplink timeout") ->
        Triple(EventKind.LORA_UP, false, "Sin respuesta del módulo al enviar")
    body.startsWith("LoRa: uplink rejected") ->
        Triple(EventKind.LORA_UP, false, "Uplink rechazado · el nodo volverá a unirse")
    body.startsWith("LoRa: join failed") ->
        Triple(EventKind.LORA_UP, false, "Join OTAA fallido · reintento en el siguiente periodo")
    body.startsWith("LoRa: joined network") ->
        Triple(EventKind.LORA_UP, true, "Unido a la red TTN")
    body.startsWith("LoRa downlink: config patch") ->
        Triple(EventKind.LORA_DOWN, true, "Downlink · parche de configuración")
    body.startsWith("LoRa downlink:") ->
        Triple(EventKind.LORA_DOWN, true, "Downlink · " + body.removePrefix("LoRa downlink:").trim())
    body.startsWith("LoRa: bad downlink") ->
        Triple(EventKind.LORA_DOWN, false, "Downlink ilegible, descartado")
    body.startsWith("inference: HS30") ->
        Triple(EventKind.LSTM, true, "Pronóstico HS30 24 h almacenado")
    body.startsWith("inference: skipped") ->
        Triple(EventKind.LSTM, false, "Inferencia omitida · " +
            body.substringAfter("-- ").substringBefore(" (status").ifBlank { "datos insuficientes" })
    body.startsWith("inference: model unavailable") ->
        Triple(EventKind.LSTM, false, "El modelo no está disponible en esta build")
    body.startsWith("sched: daily cycle") ->
        Triple(EventKind.LSTM, true, "Ciclo diario disparado")
    body.startsWith("clock: board was powered off") ->
        Triple(EventKind.SYNC, true, "Reloj recuperado por LoRa tras un apagón")
    body.startsWith("config: restored from flash") ->
        Triple(EventKind.BOOT, true, "Arranque · configuración restaurada de flash")
    body.startsWith("storage: back-filled") ->
        Triple(EventKind.SYNC, true, "Lecturas provisionales fechadas al sincronizar")
    else -> null
}
