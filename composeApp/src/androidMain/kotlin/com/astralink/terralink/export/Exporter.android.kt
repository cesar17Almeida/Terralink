package com.astralink.terralink.export

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File

actual class Exporter internal constructor(private val context: Context) {
    actual fun shareText(content: String, fileName: String, mimeType: String) =
        share(File(context.cacheDir, "exports").apply { mkdirs() }
            .let { File(it, fileName).also { f -> f.writeText(content) } }, mimeType)

    actual fun shareBytes(content: ByteArray, fileName: String, mimeType: String) =
        share(File(context.cacheDir, "exports").apply { mkdirs() }
            .let { File(it, fileName).also { f -> f.writeBytes(content) } }, mimeType)

    private fun share(file: File, mimeType: String) {
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, file)

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "Compartir lecturas").apply {
            // Required when launching from non-Activity context.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}

@Composable
actual fun rememberExporter(): Exporter {
    val ctx = LocalContext.current
    return remember(ctx) { Exporter(ctx) }
}
