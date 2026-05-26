package com.astralink.terralink.ble.session

/**
 * Events emitted while a firmware/model blob is being pushed to Savia.
 *
 * Flow:
 *   Starting -> WaitingForPsm -> Transferring(0, total) ... -> Verifying -> Success
 *                                                                     \--> Failure(reason)
 *
 * The UI maps these to a progress bar + status label. `Transferring`
 * carries `bytesSent` so the bar can advance smoothly.
 */
sealed class BlobProgress {
    data object Starting : BlobProgress()
    data object WaitingForPsm : BlobProgress()
    data class Transferring(val bytesSent: Long, val totalBytes: Long) : BlobProgress() {
        val fraction: Float
            get() = if (totalBytes > 0) bytesSent.toFloat() / totalBytes else 0f
    }
    data object Verifying : BlobProgress()
    data object Success : BlobProgress()
    data class Failure(val reason: String) : BlobProgress()
}
