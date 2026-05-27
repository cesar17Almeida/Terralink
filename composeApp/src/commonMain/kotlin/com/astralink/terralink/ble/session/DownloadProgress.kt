package com.astralink.terralink.ble.session

import com.astralink.terralink.ble.protocol.Reading

/**
 * Progress events emitted by the streaming variant of requestRawReadings.
 * The UI bar can move on each Chunk; the final list of decoded readings
 * arrives in Complete.
 */
sealed class DownloadProgress {
    data class Chunk(val received: Int, val total: Int) : DownloadProgress() {
        val fraction: Float
            get() = if (total > 0) received.toFloat() / total else 0f
    }
    data class Complete(val readings: List<Reading>) : DownloadProgress()
}
