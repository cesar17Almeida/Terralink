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

/**
 * Persistent history of the LoRa AT console, per station. Backed by the same
 * SQLDelight TerralinkDb as the readings cache, so the chat survives app
 * restarts. Process-singleton, initialised at app launch (alongside
 * ReadingsRepository).
 */
object LoraConsoleRepository {
    private var db: TerralinkDb? = null

    fun init(db: TerralinkDb) {
        if (this.db == null) this.db = db
    }

    private fun queries() =
        (db ?: error("LoraConsoleRepository not initialized. Call init(createTerralinkDb()) at app launch."))
            .loraConsoleQueries

    fun append(stationId: String, e: AtLogEntry) {
        queries().insertMessage(
            station_id = stationId,
            ts_ms = e.tsMs,
            from_user = if (e.fromUser) 1L else 0L,
            body = e.body,
            is_error = if (e.isError) 1L else 0L,
        )
    }

    fun history(stationId: String, limit: Long = 500): List<AtLogEntry> =
        queries().selectByStation(stationId, limit).executeAsList().map {
            AtLogEntry(
                tsMs = it.ts_ms,
                fromUser = it.from_user == 1L,
                body = it.body,
                isError = it.is_error == 1L,
            )
        }

    fun clear(stationId: String) = queries().deleteByStation(stationId)
}
