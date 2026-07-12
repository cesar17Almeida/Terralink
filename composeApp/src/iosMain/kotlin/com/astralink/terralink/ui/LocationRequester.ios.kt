package com.astralink.terralink.ui

import androidx.compose.runtime.Composable

// iOS location capture isn't wired yet; the app falls back to manual lat/lon entry.
@Composable
actual fun rememberLocationRequester(): LocationRequester? = null
