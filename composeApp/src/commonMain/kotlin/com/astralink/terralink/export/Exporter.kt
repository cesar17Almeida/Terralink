package com.astralink.terralink.export

import androidx.compose.runtime.Composable

/**
 * Drops a text payload into a file and pops the platform share sheet.
 * Android: FileProvider + Intent.ACTION_SEND. iOS:
 * UIActivityViewController over the temp file URL. JVM writes to
 * ~/Downloads and surfaces the path.
 */
expect class Exporter {
    fun shareText(content: String, fileName: String, mimeType: String)
    fun shareBytes(content: ByteArray, fileName: String, mimeType: String)
}

@Composable
expect fun rememberExporter(): Exporter
