package com.astralink.terralink.export

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

actual fun ImageBitmap.toPngBytes(): ByteArray {
    val image = Image.makeFromBitmap(asSkiaBitmap())
    return image.encodeToData(EncodedImageFormat.PNG)?.bytes ?: ByteArray(0)
}
