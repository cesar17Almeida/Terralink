package com.astralink.terralink.model

import kotlinx.serialization.Serializable

/**
 * A Savia station the user has paired with. Identified by its BLE id
 * (platform-defined, see ScannedDevice.id). `displayName` is what the
 * device advertised at pairing time -- the user will likely want to
 * rename it later, but that's a v2 feature.
 *
 * Marked @Serializable so StationsRepository can JSON-encode the full
 * list inside a single DataStore preference entry.
 */
@Serializable
data class SavedStation(
    val bleId: String,
    val displayName: String,
    val pairedAtMs: Long,
    /** Epoch ms of the last successful sync; null if never synced. */
    val lastSyncMs: Long? = null,
    /** Epoch ms of the last on-demand LoRa ping (rate-limits uplinks); null if never. */
    val lastLoraPingMs: Long? = null,
)
