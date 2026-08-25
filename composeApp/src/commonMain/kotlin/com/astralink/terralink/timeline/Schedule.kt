package com.astralink.terralink.timeline

import com.astralink.terralink.ble.protocol.ConfigSnapshotMsg
import com.astralink.terralink.ble.protocol.SensorInfo
import com.astralink.terralink.ble.protocol.SensorType

// The firmware's own bounds (savia/config.h). Projecting with an out-of-range
// period would draw a future the station will never run: it clamps first.
private const val LORA_PERIOD_MIN_S = 300
private const val LORA_PERIOD_MAX_S = 86_400
private const val CAPTURE_MIN_S = 60

private const val MS_PER_S = 1_000L
private const val MS_PER_MIN = 60_000L
private const val MS_PER_DAY = 86_400_000L

/** What the projection needs to know about the station's recent past to anchor
 *  the future on it. Every field is optional: with none of them the projection
 *  still runs, anchored on `now`, and just says "one interval from now". */
data class ScheduleAnchors(
    val nowMs: Long,
    val lastSampleByPort: Map<Int, Long> = emptyMap(),
    val lastUplinkMs: Long? = null,
    val lastDailyMs: Long? = null,
)

/**
 * The station's future, computed on the phone from its config -- no firmware
 * change, no new BLE message.
 *
 * This mirrors `scheduler_tick` / `lora_cycle` rather than inventing a plausible
 * cadence, so what the timeline draws to the right of "ahora" is what the station
 * will actually do:
 *
 *  - Per-sensor captures run on `interval_s` (or the global `capture_s`), anchored
 *    to the LAST ACTUAL SAMPLE, not to a clock boundary. The firmware anchors them
 *    to boot; the newest reading is the only visible trace of that phase, so it is
 *    what we count from.
 *  - The LoRa cycle runs on `lora_period_s` (clamped), anchored the same way to the
 *    last uplink.
 *  - The daily cycle fires at LOCAL `daily_hour:daily_min`, once per local day, and
 *    drags a full capture sweep with it (the firmware forces every sensor due so
 *    the newest hourly bucket is real before it infers). In LOCAL inference mode it
 *    also runs the LSTM.
 *
 * Downlinks are deliberately NOT projected: whether one arrives in the RX window
 * after an uplink is the network's decision, not the station's schedule.
 */
fun projectSchedule(
    config: ConfigSnapshotMsg,
    anchors: ScheduleAnchors,
    untilMs: Long,
    maxEvents: Int = 600,
): List<StationEvent> {
    val now = anchors.nowMs
    if (untilMs <= now) return emptyList()
    val out = mutableListOf<StationEvent>()

    // --- per-sensor captures -------------------------------------------------
    val sampled = config.sensors.filter { !isOutputSlot(it) }
    for (s in sampled) {
        val stepS = (if (s.intervalS > 0) s.intervalS else config.captureS)
            .coerceAtLeast(CAPTURE_MIN_S)
        val step = stepS * MS_PER_S
        val anchor = anchors.lastSampleByPort[s.port] ?: now
        var t = anchor + step
        while (t <= now) t += step          // catch up, exactly like scheduler_tick
        var guard = 0
        while (t < untilMs && guard++ < maxEvents) {
            out += StationEvent(
                tsMs = t, kind = EventKind.SAMPLE, port = s.port, future = true,
                detail = "Lectura programada · cada ${humanSeconds(stepS)}",
            )
            t += step
        }
    }

    // --- LoRa uplinks --------------------------------------------------------
    if (config.loraPeriodS > 0) {
        val periodS = config.loraPeriodS.coerceIn(LORA_PERIOD_MIN_S, LORA_PERIOD_MAX_S)
        val step = periodS * MS_PER_S
        val anchor = anchors.lastUplinkMs ?: now
        var t = anchor + step
        while (t <= now) t += step
        var guard = 0
        while (t < untilMs && guard++ < maxEvents) {
            out += StationEvent(
                tsMs = t, kind = EventKind.LORA_UP, future = true,
                detail = "Envío programado · cada ${humanSeconds(periodS)}",
            )
            t += step
        }
    }

    // --- daily cycle ---------------------------------------------------------
    val local = config.inferenceMode == "local"
    var day = nextDailyMs(
        nowMs = now,
        hour = config.dailyHour,
        minute = config.dailyMin,
        offsetMin = config.utcOffsetMin,
        lastDailyMs = anchors.lastDailyMs,
    )
    var guard = 0
    while (day < untilMs && guard++ < 32) {
        val at = formatHm(config.dailyHour, config.dailyMin)
        // The sweep the daily cycle forces on every sensor, so the model infers
        // over a real newest hour rather than a copied one.
        for (s in sampled) {
            out += StationEvent(
                tsMs = day, kind = EventKind.SAMPLE, port = s.port, future = true,
                detail = "Barrido del ciclo diario ($at)",
            )
        }
        out += StationEvent(
            tsMs = day, kind = EventKind.LSTM, future = true,
            detail = if (local) "Inferencia LSTM en la estación · pronóstico HS30 24 h"
                     else "Ciclo diario ($at) · el LSTM corre fuera (modo reenvío)",
        )
        day += MS_PER_DAY
    }

    return out.sortedBy { it.tsMs }
}

/** Output-only slots are driven, never sampled: the firmware clears their due
 *  time, so they contribute nothing to the future track. */
private fun isOutputSlot(s: SensorInfo): Boolean = s.type == SensorType.ACTUATOR

/**
 * The next instant the LOCAL time of day reaches [hour]:[minute]. The firmware
 * fires once per LOCAL day on a `>=` threshold, so "today" still counts when the
 * moment has already passed but nothing fired yet today ([lastDailyMs] falls on an
 * earlier local day) -- the station catches up on its next tick rather than losing
 * the day, and we say so by scheduling it for now.
 */
internal fun nextDailyMs(
    nowMs: Long,
    hour: Int,
    minute: Int,
    offsetMin: Int,
    lastDailyMs: Long?,
): Long {
    val offset = offsetMin * MS_PER_MIN
    val localNow = nowMs + offset
    val today = floorDiv(localNow, MS_PER_DAY)
    val fireOfDay = (hour * 60L + minute) * MS_PER_MIN
    val todayFire = today * MS_PER_DAY + fireOfDay
    val firedToday = lastDailyMs?.let { floorDiv(it + offset, MS_PER_DAY) == today } ?: false
    val localFire = when {
        firedToday -> todayFire + MS_PER_DAY
        localNow < todayFire -> todayFire
        else -> localNow                       // overdue: due on the next tick
    }
    return localFire - offset
}

/** Floor division that stays correct for negative operands (pre-1970 local
 *  instants can't happen here, but the offset can push a value negative). */
private fun floorDiv(a: Long, b: Long): Long {
    val q = a / b
    return if (a % b != 0L && (a xor b) < 0) q - 1 else q
}

private fun formatHm(h: Int, m: Int): String =
    h.toString().padStart(2, '0') + ":" + m.toString().padStart(2, '0')

/** A cadence in seconds as the installer set it: "15 min", "1 h", "45 s". */
internal fun humanSeconds(s: Int): String = when {
    s % 86_400 == 0 && s >= 86_400 -> "${s / 86_400} d"
    s % 3_600 == 0 && s >= 3_600 -> "${s / 3_600} h"
    s % 60 == 0 && s >= 60 -> "${s / 60} min"
    else -> "$s s"
}
