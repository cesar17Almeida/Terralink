package com.astralink.terralink.state

import com.astralink.terralink.db.TerralinkDb

/** One AT-console line: the text, who sent it, whether it was an error, and the
 *  phone wall-clock timestamp of when it was logged. */
data class AtLogEntry(
    val tsMs: Long,
    val fromUser: Boolean,
    val body: String,
    val isError: Boolean,
)

/** Console channels persisted in the same table (per station). */
object ConsoleChannel {
    const val LORA = "lora"    // LoRa AT terminal (Wio-E5)
    const val SDI12 = "sdi12"  // SDI-12 probe console
}

/**
 * Persistent history of the console chats, per station and per [channel] (LoRa AT vs
 * SDI-12 probe). Backed by the same SQLDelight TerralinkDb as the readings cache, so the
 * chats survive app restarts. Process-singleton, initialised at app launch (alongside
 * ReadingsRepository). `channel` defaults to "lora" for the existing LoRa console call sites.
 */
object LoraConsoleRepository {
    private var db: TerralinkDb? = null

    fun init(db: TerralinkDb) {
        if (this.db == null) this.db = db
    }

    private fun queries() =
        (db ?: error("LoraConsoleRepository not initialized. Call init(createTerralinkDb()) at app launch."))
            .loraConsoleQueries

    fun append(stationId: String, e: AtLogEntry, channel: String = ConsoleChannel.LORA) {
        queries().insertMessage(
            station_id = stationId,
            channel = channel,
            ts_ms = e.tsMs,
            from_user = if (e.fromUser) 1L else 0L,
            body = e.body,
            is_error = if (e.isError) 1L else 0L,
        )
    }

    fun history(stationId: String, channel: String = ConsoleChannel.LORA, limit: Long = 500): List<AtLogEntry> =
        queries().selectByStation(stationId, channel, limit).executeAsList().map {
            AtLogEntry(
                tsMs = it.ts_ms,
                fromUser = it.from_user == 1L,
                body = it.body,
                isError = it.is_error == 1L,
            )
        }

    fun clear(stationId: String, channel: String = ConsoleChannel.LORA) =
        queries().deleteByStation(stationId, channel)
}
