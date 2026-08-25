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
