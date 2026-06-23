package com.astralink.terralink.export

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.Desktop
import java.io.File

actual class Exporter {
    // No real share sheet on Desktop; save into ~/Downloads (or pwd if
    // unavailable) and pop the OS file browser at that location.
    actual fun shareText(content: String, fileName: String, mimeType: String) =
        saveAndReveal(fileName) { it.writeText(content) }

    actual fun shareBytes(content: ByteArray, fileName: String, mimeType: String) =
        saveAndReveal(fileName) { it.writeBytes(content) }

    private fun saveAndReveal(fileName: String, write: (File) -> Unit) {
        val home = System.getProperty("user.home") ?: "."
        val dir = File(home, "Downloads").takeIf { it.exists() }
            ?: File(System.getProperty("user.dir") ?: ".")
        write(File(dir, fileName))
        runCatching { Desktop.getDesktop().open(dir) }
    }
}

@Composable
actual fun rememberExporter(): Exporter = remember { Exporter() }
