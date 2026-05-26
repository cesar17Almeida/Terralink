package com.astralink.terralink.ui

import androidx.compose.runtime.Composable

@Composable
actual fun rememberFirmwareFilePicker(onPick: (PickedFile) -> Unit): () -> Unit =
    { throw UnsupportedOperationException("File picker is not implemented on Desktop.") }
