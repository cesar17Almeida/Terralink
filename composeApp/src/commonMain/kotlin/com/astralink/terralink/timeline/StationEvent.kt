package com.astralink.terralink.timeline

/**
 * The kinds of thing a station does. Shared by the journal (what it did) and the
 * schedule projection (what it will do), so one mark on the timeline renders the
 * same either side of "ahora" -- filled in the past, hollow in the future.
 */
enum class EventKind {
    SAMPLE,     // a sensor was read
    LORA_UP,    // an uplink left the node
    LORA_DOWN,  // a downlink came back (time + TA, or a config patch)
    LSTM,       // the daily inference ran
    SYNC,       // the wall clock was set (BLE or LoRa)
    BOOT,       // the station came up
}

/**
 * One moment on the timeline. [future] separates a projected event from a
 * recorded one -- everything else about them is identical, which is the point:
 * the timeline is one continuous track, not a past view glued to a plan.
 *
 * [port] is the sensor for SAMPLE and -1 for station-wide events; the journal
 * stores that sentinel rather than NULL so its primary key dedups (SQLite treats
 * NULLs as distinct).
 */
data class StationEvent(
    val tsMs: Long,
    val kind: EventKind,
    val port: Int = NO_PORT,
    val ok: Boolean = true,
    val detail: String = "",
    val future: Boolean = false,
) {
    companion object {
        const val NO_PORT = -1
    }
}

/**
 * How far apart two marks of this kind can be and still be one event. One radio
 * cycle reaches the app as a status stamp (ms, when it started) and as log lines
 * (whole seconds, up to ~40 s later); the boot instant is recomputed from a
 * truncated uptime on every read; the model run is derived to the hour from its
 * forecast. A capture carries an exact stamp.
 */
fun EventKind.sameEventWindowMs(): Long = when (this) {
    EventKind.SAMPLE -> 0L
    EventKind.LORA_UP, EventKind.LORA_DOWN, EventKind.SYNC -> 60_000L
    EventKind.BOOT -> 120_000L
    EventKind.LSTM -> 3_600_000L
}

/** Split [items], sorted by [ts], into runs that start within [windowMs] of the
 *  run's first item. Anchoring on the first bounds how long a run can grow. */
internal fun <T> clusterWithin(items: List<T>, windowMs: Long, ts: (T) -> Long): List<List<T>> {
    val runs = mutableListOf<MutableList<T>>()
    for (item in items) {
        val run = runs.lastOrNull()
        if (run != null && ts(item) - ts(run.first()) <= windowMs) run += item
        else runs += mutableListOf(item)
    }
    return runs
}

/** Wire token for the `kind` column. Kept explicit so renaming the enum can't
 *  silently orphan a journal the user already accumulated. */
fun EventKind.token(): String = when (this) {
    EventKind.SAMPLE -> "sample"
    EventKind.LORA_UP -> "lora_up"
    EventKind.LORA_DOWN -> "lora_down"
    EventKind.LSTM -> "lstm"
    EventKind.SYNC -> "sync"
    EventKind.BOOT -> "boot"
}

fun eventKindFrom(token: String): EventKind? = when (token) {
    "sample" -> EventKind.SAMPLE
    "lora_up" -> EventKind.LORA_UP
    "lora_down" -> EventKind.LORA_DOWN
    "lstm" -> EventKind.LSTM
    "sync" -> EventKind.SYNC
    "boot" -> EventKind.BOOT
    else -> null   // a journal written by a newer build: skip, don't crash
}
