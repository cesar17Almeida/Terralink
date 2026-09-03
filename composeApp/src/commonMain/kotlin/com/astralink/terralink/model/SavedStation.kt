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
    /** Board wall clock (epoch ms) at the last status read; null if never seen. */
    val clockMs: Long? = null,
    /** Phone time (epoch ms) when [clockMs] was read; ticks the home clock forward. */
    val clockReadAtMs: Long? = null,
    /** Station-configured UTC offset (min) at that read; renders its local time. */
    val clockOffsetMin: Int? = null,
    /**
     * First-run wizard dismissed while the station still reported factory defaults.
     * Forgotten as soon as it reports a saved config, so a reflash or a factory
     * reset offers the wizard again.
     */
    val setupSkipped: Boolean = false,
)
