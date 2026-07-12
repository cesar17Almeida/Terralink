package com.astralink.terralink.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager

/**
 * Tap anywhere on this container (outside a focused field) to drop focus and
 * hide the soft keyboard. Put it on a screen's root layout so every text field
 * underneath shares the behaviour. Dialogs render in their own window, so a
 * screen modifier never reaches them -- [TerraDialog] applies this itself.
 */
@Composable
fun Modifier.dismissKeyboardOnTap(): Modifier {
    val focusManager = LocalFocusManager.current
    return this.pointerInput(Unit) {
        detectTapGestures(onTap = { focusManager.clearFocus() })
    }
}
