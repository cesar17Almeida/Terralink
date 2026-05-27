package com.astralink.terralink.export

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.Desktop
import java.io.File

actual class Exporter {
    actual fun shareText(content: String, fileName: String, mimeType: String) {
        // No real share sheet on Desktop; save into ~/Downloads (or pwd if
        // unavailable) and pop the OS file browser at that location.
        val home = System.getProperty("user.home") ?: "."
        val dir = File(home, "Downloads").takeIf { it.exists() }
            ?: File(System.getProperty("user.dir") ?: ".")
        val file = File(dir, fileName)
        file.writeText(content)
        runCatching { Desktop.getDesktop().open(dir) }
    }
}

@Composable
actual fun rememberExporter(): Exporter = remember { Exporter() }
