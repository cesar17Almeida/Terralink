package com.astralink.terralink.ui

import androidx.compose.runtime.Composable

// Desktop has no device GPS; the app falls back to manual lat/lon entry.
@Composable
actual fun rememberLocationRequester(): LocationRequester? = null
