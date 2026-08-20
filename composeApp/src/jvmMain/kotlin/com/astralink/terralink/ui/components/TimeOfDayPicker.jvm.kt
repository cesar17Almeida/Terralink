package com.astralink.terralink.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

// The desktop has no native time picker, so this is the Material 3 one in a dialog.
// Same contract as the phone builds: it hands back hour + minute, nothing else.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
actual fun rememberTimeOfDayPicker(onPick: (hour: Int, minute: Int) -> Unit): (Int, Int) -> Unit {
    var request by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    val pending = request
    if (pending != null) {
        val state = rememberTimePickerState(
            initialHour = pending.first,
            initialMinute = pending.second,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { request = null },
            title = { Text("Hora de predicción") },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    onPick(state.hour, state.minute)
                    request = null
                }) { Text("Listo") }
            },
            dismissButton = {
                TextButton(onClick = { request = null }) { Text("Cancelar") }
            },
        )
    }

    return remember(onPick) { { hour, minute -> request = hour to minute } }
}
