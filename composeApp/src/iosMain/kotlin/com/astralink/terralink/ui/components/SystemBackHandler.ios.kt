package com.astralink.terralink.ui.components

import androidx.compose.runtime.Composable

// No system back action: every screen shows its own back arrow.
@Composable
actual fun SystemBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit
