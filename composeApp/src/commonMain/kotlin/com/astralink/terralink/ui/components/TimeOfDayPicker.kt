package com.astralink.terralink.ui.components

import androidx.compose.runtime.Composable

/**
 * Returns a launcher: call it with the time currently shown and the platform's own
 * time-of-day picker appears there. [onPick] fires with the chosen hour (0..23) and
 * minute (0..59); cancelling does nothing.
 *
 * The station stores the daily cycle as two fields (hour + minute), so the picker
 * hands back exactly that -- no date, no seconds, no time zone.
 *
 * Implementations:
 *  - Android: android.app.TimePickerDialog, the OS dialog; follows the phone's
 *             12/24 h setting.
 *  - iOS:     UIDatePicker wheels in a sheet presented from the host controller.
 *  - JVM:     Material 3 TimePicker in a dialog -- the desktop has no native one.
 */
@Composable
expect fun rememberTimeOfDayPicker(onPick: (hour: Int, minute: Int) -> Unit): (Int, Int) -> Unit
