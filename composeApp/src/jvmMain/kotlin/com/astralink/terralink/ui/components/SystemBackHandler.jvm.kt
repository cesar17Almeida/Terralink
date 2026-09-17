package com.astralink.terralink.ui.components

import androidx.compose.runtime.Composable

// No system back action on the desktop build.
@Composable
actual fun SystemBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit
