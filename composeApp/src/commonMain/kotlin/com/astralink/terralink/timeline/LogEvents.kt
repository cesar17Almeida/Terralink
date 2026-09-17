package com.astralink.terralink.timeline

private const val MS_PER_DAY = 86_400_000L

/**
 * Recover past events from the firmware's log ring (`data_request kind="logs"`).
 *
 * The ring holds the station's last few dozen lines of at most 80 chars, prefixed
 * `HH:MM:SS ` once the wall clock is synced (`src/system/log.c`), and it is the
 * only place the station says out loud that an uplink left, a downlink came back
 * or the LSTM ran -- its status message only carries "the last time" for a couple
 * of those. Parsing it costs nothing on the station and needs no firmware change;
 * the journal then keeps what we read before the ring rotates it away.
 *
 * Lines with a `+Ns ` prefix (clock not yet synced) are skipped: an event we can't
 * place in time is worse than no event on a timeline.
 *
 * One radio cycle prints several lines over some seconds -- the cycle, the join,
 * the frame that left, the signal it was answered with, what came down. The lines
 * of one kind within [sameEventWindowMs] of each other are merged into a single
 * mark, stamped at the first, whose detail reads as one line.
 */
fun parseLogEvents(lines: List<String>, stationNowMs: Long): List<StationEvent> {
    val marks = lines.flatMap { line ->
        val (tsMs, body) = parseStamp(line, stationNowMs) ?: return@flatMap emptyList()
        classify(body).map { tsMs to it }
    }
    return marks
        .groupBy { (_, m) -> m.kind }
        .flatMap { (kind, group) ->
            clusterWithin(group.sortedBy { it.first }, kind.sameEventWindowMs()) { it.first }
                .map { run -> merge(run.first().first, kind, run.map { it.second }) }
        }
        .sortedBy { it.tsMs }
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
    // WARN lines carry a "! " marker after the stamp (for the logs screen); the
    // words that follow are what classify() knows.
    return ts to line.substring(9).removePrefix("! ")
}

private fun floorMod(a: Long, b: Long): Long {
    val r = a % b
    return if (r < 0) r + b else r
}

/**
 * What one log line contributes to a mark. A MAIN line describes the event. A
 * SUFFIX (signal strength) is appended to a description and dropped when there is
 * none. A FALLBACK only stands when no MAIN line shares the instant.
 */
private enum class Role { MAIN, SUFFIX, FALLBACK }

private data class Mark(
    val kind: EventKind,
    val ok: Boolean,
    val detail: String,
    val role: Role = Role.MAIN,
)

private fun merge(tsMs: Long, kind: EventKind, marks: List<Mark>): StationEvent {
    val mains = marks.filter { it.role == Role.MAIN }.map { it.detail }.distinct()
    val parts = if (mains.isNotEmpty()) {
        mains + marks.filter { it.role == Role.SUFFIX }.map { it.detail }.distinct()
    } else {
        marks.filter { it.role == Role.FALLBACK }.map { it.detail }.distinct()
            .ifEmpty { marks.map { it.detail }.distinct() }
    }
    return StationEvent(tsMs = tsMs, kind = kind, ok = marks.all { it.ok }, detail = parts.joinToString(" · "))
}

private fun main(kind: EventKind, detail: String) = listOf(Mark(kind, true, detail))
private fun fail(kind: EventKind, detail: String) = listOf(Mark(kind, false, detail))

/** The INFO/WARN lines worth a mark, in the words the firmware prints them. */
private fun classify(body: String): List<Mark> = when {
    // --- uplinks -------------------------------------------------------------
    body.startsWith("LoRa: uplink sent") ->
        main(EventKind.LORA_UP, "Uplink entregado · sin downlink en la ventana RX")
    body.startsWith("LoRa: uplink timeout") ->
        fail(EventKind.LORA_UP, "Sin respuesta del módulo al enviar")
    body.startsWith("LoRa: uplink rejected") ->
        fail(EventKind.LORA_UP, "Uplink rechazado · el nodo volverá a unirse")
    body.startsWith("LoRa: uplink ") ->
        main(EventKind.LORA_UP, uplinkFrameDetail(body))
    body.startsWith("LoRa: cycle due") ->
        listOf(Mark(EventKind.LORA_UP, true, "Ciclo LoRa · " + periodWord(body), Role.FALLBACK))
    body.startsWith("LoRa: module silent") ->
        fail(EventKind.LORA_UP, "Módulo LoRa sin respuesta · reintento en el siguiente periodo")
    body.startsWith("LoRa: joining") ->
        listOf(Mark(EventKind.LORA_UP, true, "Join OTAA · solicitando unión a la red", Role.FALLBACK))
    body.startsWith("LoRa: join failed") || body.startsWith("LoRa: ping join failed") ->
        fail(EventKind.LORA_UP, "Join OTAA fallido · reintento en el siguiente periodo")
    body.startsWith("LoRa: joined network") || body.startsWith("LoRa: ping joined network") ->
        listOf(Mark(EventKind.LORA_UP, true, "Unido a la red TTN", Role.SUFFIX))
    // --- downlinks -----------------------------------------------------------
    body.startsWith("LoRa: signal RSSI") -> signalMarks(body)
    body.startsWith("LoRa downlink: config patch") ->
        main(EventKind.LORA_DOWN, "Downlink · parche de configuración")
    body.startsWith("LoRa downlink:") -> downlinkMarks(body)
    body.startsWith("LoRa: bad downlink") ->
        fail(EventKind.LORA_DOWN, "Downlink ilegible" + sizeWord(body) + " · descartado")
    body.startsWith("LoRa: implausible downlink clock") ->
        fail(EventKind.LORA_DOWN, "Downlink con una hora inverosímil · ignorado")
    body.startsWith("clock: LoRa time held") ->
        listOf(Mark(EventKind.LORA_DOWN, true, "hora retenida hasta que otro downlink la confirme", Role.SUFFIX))
    body.startsWith("LoRa config patch:") ->
        main(EventKind.LORA_DOWN, "Configuración recibida por LoRa · " + appliedWord(body))
    // --- the model, the clock, the boot -------------------------------------
    body.startsWith("inference: HS30") ->
        main(EventKind.LSTM, "Pronóstico HS30 24 h almacenado")
    body.startsWith("inference: skipped") ->
        fail(EventKind.LSTM, "Inferencia omitida · " +
            body.substringAfter("-- ").substringBefore(" (status").ifBlank { "datos insuficientes" })
    body.startsWith("inference: model unavailable") ->
        fail(EventKind.LSTM, "El modelo no está disponible en esta build")
    body.startsWith("sched: daily cycle") ->
        main(EventKind.LSTM, "Ciclo diario disparado")
    body.startsWith("clock: board was powered off") ->
        main(EventKind.SYNC, "Reloj recuperado por LoRa tras un apagón")
    body.startsWith("clock: two LoRa downlinks agree") ->
        main(EventKind.SYNC, "Reloj corregido por LoRa · dos downlinks coinciden")
    body.startsWith("clock: moved back") ->
        main(EventKind.SYNC, "Reloj retrasado · lecturas fechadas en el futuro corregidas")
    body.startsWith("config: restored from flash") ->
        main(EventKind.BOOT, "Arranque · configuración restaurada de flash")
    body.startsWith("storage: back-filled") ->
        main(EventKind.SYNC, "Lecturas provisionales fechadas al sincronizar")
    else -> emptyList()
}

/** "LoRa: uplink soil 23 B (unconfirmed)" -> what the frame was for. */
private fun uplinkFrameDetail(body: String): String {
    val words = body.removePrefix("LoRa: uplink ").split(' ')
    val frame = words.getOrNull(0) ?: ""
    val bytes = words.getOrNull(1)?.toIntOrNull()?.let { " · $it B" } ?: ""
    val what = when (frame) {
        "boot" -> "Uplink BOOT · pide la hora al backend"
        "soil" -> "Uplink SOIL · agregados horarios de la sonda"
        "coords" -> "Uplink COORDS · coordenadas de la estación"
        "cfg_ack" -> "Uplink CFG_ACK · confirma la configuración recibida"
        "forecast" ->
            if (body.contains("(confirmed)")) "Ping · uplink confirmado"
            else "Uplink FORECAST · mínimo HS30 previsto"
        else -> "Uplink ${frame.uppercase()}"
    }
    return what + bytes
}

/** "LoRa: cycle due (period=300s)" -> "periodo 300 s". */
private fun periodWord(body: String): String =
    Regex("""period=(\d+)s""").find(body)?.let { "periodo ${it.groupValues[1]} s" } ?: "periodo por defecto"

/** "LoRa: signal RSSI -71 dBm, SNR 10.0 dB": the module measured a frame it
 *  received -- an ACK or a data downlink -- so this is the downlink lane's proof. */
private fun signalMarks(body: String): List<Mark> {
    val signal = body.removePrefix("LoRa: signal ").replace(", ", " · ")
    return listOf(
        Mark(EventKind.LORA_DOWN, true, "Recepción del gateway · $signal", Role.FALLBACK),
        Mark(EventKind.LORA_DOWN, true, signal, Role.SUFFIX),
    )
}

/** "LoRa downlink: 8 B, 0 past + 0 future TA, clock set" -> the downlink and, when
 *  it carried the time, the clock sync it caused. Older firmware omits the size. */
private fun downlinkMarks(body: String): List<Mark> {
    val rest = body.removePrefix("LoRa downlink:").trim()
    val ta = Regex("""(\d+) past \+ (\d+) future""").find(rest)
    val hours = ta?.let { it.groupValues[1].toInt() + it.groupValues[2].toInt() } ?: 0
    val clock = rest.contains("clock set")
    val detail = when {
        clock && hours > 0 -> "Downlink · hora y temperatura del aire ($hours h)"
        clock -> "Downlink · hora"
        hours > 0 -> "Downlink · temperatura del aire ($hours h)"
        ta == null -> "Downlink · $rest"
        else -> "Downlink · sin hora ni temperatura"
    }
    val marks = mutableListOf(Mark(EventKind.LORA_DOWN, true, detail))
    if (clock) marks += Mark(EventKind.SYNC, true, "Reloj puesto en hora por LoRa")
    return marks
}

/** " (10 B)" out of "LoRa: bad downlink (10 B)". */
private fun sizeWord(body: String): String =
    Regex("""\((\d+) B\)""").find(body)?.let { " (${it.groupValues[1]} B)" } ?: ""

/** "LoRa config patch: 1 applied, 0 rejected" -> "1 campo aplicado, 0 rechazados". */
private fun appliedWord(body: String): String {
    val m = Regex("""(\d+) applied, (\d+) rejected""").find(body)
        ?: return body.substringAfter(':').trim()
    val (a, r) = m.destructured
    return "$a ${if (a == "1") "campo aplicado" else "campos aplicados"}, " +
        "$r ${if (r == "1") "rechazado" else "rechazados"}"
}
