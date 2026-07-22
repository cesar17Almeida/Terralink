package com.astralink.terralink.util

import com.astralink.terralink.model.SavedStation

/** How fresh a BLE clock read must be to count as "we just talked to it". */
const val CLOCK_FRESH_MS: Long = 2 * 60_000L

/** True when the station's clock was read over BLE within [CLOCK_FRESH_MS]. */
fun hasFreshClock(station: SavedStation, nowTickMs: Long): Boolean {
    val readAt = station.clockReadAtMs ?: return false
    return nowTickMs - readAt < CLOCK_FRESH_MS
}

/**
 * Board clock line: the last BLE-read station clock, ticked forward with phone
 * time and shifted to the station's configured UTC offset. Null when the clock
 * has never been read.
 */
fun stationClockText(station: SavedStation, nowTickMs: Long): String? {
    val clock = station.clockMs ?: return null
    val readAt = station.clockReadAtMs ?: return null
    val localMs = clock + (nowTickMs - readAt) + (station.clockOffsetMin ?: 0) * 60_000L
    val secOfDay = ((localMs / 1000) % 86_400 + 86_400) % 86_400
    val h = (secOfDay / 3600).toString().padStart(2, '0')
    val m = ((secOfDay % 3600) / 60).toString().padStart(2, '0')
    val s = (secOfDay % 60).toString().padStart(2, '0')
    val stale = nowTickMs - readAt > 60 * 60_000L
    return "Hora placa $h:$m:$s" +
        if (stale) " · leída ${formatRelativeMs(readAt, nowTickMs)}" else ""
}
