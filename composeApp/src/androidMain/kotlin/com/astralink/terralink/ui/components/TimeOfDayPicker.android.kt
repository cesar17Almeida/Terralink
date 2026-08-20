package com.astralink.terralink.ui.components

import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberTimeOfDayPicker(onPick: (hour: Int, minute: Int) -> Unit): (Int, Int) -> Unit {
    val context = LocalContext.current
    return remember(context, onPick) {
        { hour, minute ->
            TimePickerDialog(
                context,
                { _, h, m -> onPick(h, m) },
                hour,
                minute,
                DateFormat.is24HourFormat(context),   // respect the system 12/24 h choice
            ).show()
        }
    }
}
