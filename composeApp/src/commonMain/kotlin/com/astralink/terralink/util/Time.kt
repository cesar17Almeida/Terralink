package com.astralink.terralink.util

/** Current wall-clock time as epoch milliseconds. */
expect fun nowMs(): Long

/** A signal/reading older than this is rendered as stale (faded) in the UI. */
const val STALE_AFTER_MS: Long = 12 * 60 * 60 * 1000L

/** "hace 30 min" style relative time from an epoch-ms instant to [now]. */
fun formatRelativeMs(ms: Long, now: Long = nowMs()): String {
    val delta = now - ms
    val minutes = delta / 60_000
    return when {
        delta < 0 -> "ahora"
        delta < 30_000 -> "hace instantes"
        minutes < 1 -> "hace menos de 1 min"
        minutes < 60 -> "hace $minutes min"
        minutes < 1440 -> "hace ${minutes / 60} h"
        else -> "hace ${minutes / 1440} d"
    }
}

/** True when [ms] is missing or older than [STALE_AFTER_MS] (shown faded). */
fun isStale(ms: Long?, now: Long = nowMs()): Boolean = ms == null || now - ms > STALE_AFTER_MS
