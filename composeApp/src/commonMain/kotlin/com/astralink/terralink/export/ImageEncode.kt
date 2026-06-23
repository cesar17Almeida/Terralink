package com.astralink.terralink.export

import androidx.compose.ui.graphics.ImageBitmap

/** Encode a captured composable bitmap to PNG bytes (Android: Bitmap; iOS/JVM: Skia). */
expect fun ImageBitmap.toPngBytes(): ByteArray
