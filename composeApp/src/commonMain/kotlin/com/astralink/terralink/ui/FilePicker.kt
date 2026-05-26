package com.astralink.terralink.ui

import androidx.compose.runtime.Composable

/**
 * A file the user picked from the device storage / cloud provider.
 * `bytes` is the full contents loaded into memory; the typical firmware
 * payload is well under the 256 MB MAX_BLOB_BYTES cap.
 */
data class PickedFile(
    val name: String,
    val bytes: ByteArray,
)

/**
 * Returns a launcher function: calling it presents the platform file
 * picker. On success, `onPick` is invoked with the loaded file. On
 * cancel / failure, nothing happens.
 *
 * Implementations:
 *  - Android: ActivityResultContracts.OpenDocument + ContentResolver.
 *  - iOS:     UIDocumentPickerViewController presented from the host
 *             UIViewController, NSData read into a ByteArray.
 *  - JVM:     stub that throws UnsupportedOperationException; the
 *             Desktop build isn't wired for OTA today.
 */
@Composable
expect fun rememberFirmwareFilePicker(onPick: (PickedFile) -> Unit): () -> Unit
