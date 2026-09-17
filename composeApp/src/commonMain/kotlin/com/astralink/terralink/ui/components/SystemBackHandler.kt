package com.astralink.terralink.ui.components

import androidx.compose.runtime.Composable

/**
 * Route the platform's own back action (Android's back button and gesture) to
 * [onBack], so it does what the screen's back arrow does. The most recently
 * composed enabled handler wins. No-op where the platform has no such action.
 */
@Composable
expect fun SystemBackHandler(enabled: Boolean = true, onBack: () -> Unit)
