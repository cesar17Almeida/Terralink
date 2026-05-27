package com.astralink.terralink.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.astralink.terralink.util.nowMs

/**
 * Time range presets the user can pick. `Custom` opens a from/to date
 * picker; the rest compute their bounds relative to nowMs() / the
 * station's last sync.
 */
enum class TimeRangePreset(val label: String) {
    Last24h("Últimas 24 h"),
    Last7d("Últimos 7 días"),
    Today("Hoy"),
    SinceLastSync("Desde último sync"),
    Custom("Personalizado"),
}

data class TimeRange(val fromMs: Long, val toMs: Long)

private const val MS_PER_DAY = 86_400_000L
private const val MS_PER_HOUR = 3_600_000L

fun resolveTimeRange(
    preset: TimeRangePreset,
    customFromMs: Long?,
    customToMs: Long?,
    lastSyncMs: Long?,
): TimeRange {
    val now = nowMs()
    return when (preset) {
        TimeRangePreset.Last24h -> TimeRange(now - 24 * MS_PER_HOUR, now)
        TimeRangePreset.Last7d  -> TimeRange(now - 7 * MS_PER_DAY, now)
        TimeRangePreset.Today   -> {
            // Start of today UTC -- coarse but no kotlinx-datetime dep yet.
            val dayStart = (now / MS_PER_DAY) * MS_PER_DAY
            TimeRange(dayStart, now)
        }
        TimeRangePreset.SinceLastSync -> TimeRange(lastSyncMs ?: (now - 24 * MS_PER_HOUR), now)
        TimeRangePreset.Custom -> TimeRange(
            fromMs = customFromMs ?: (now - 24 * MS_PER_HOUR),
            toMs = customToMs ?: now,
        )
    }
}

/**
 * Stacked preset chips + an inline custom date picker when the user
 * chooses Custom. Notifies the caller on every change so it can keep
 * its computed (fromMs, toMs) up to date.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeRangePicker(
    preset: TimeRangePreset,
    customFromMs: Long?,
    customToMs: Long?,
    onPresetChange: (TimeRangePreset) -> Unit,
    onCustomChange: (fromMs: Long?, toMs: Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showFromPicker by remember { mutableStateOf(false) }
    var showToPicker by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TimeRangePreset.entries.forEach { p ->
                FilterChip(
                    selected = preset == p,
                    onClick = { onPresetChange(p) },
                    label = { Text(p.label) },
                )
            }
        }

        if (preset == TimeRangePreset.Custom) {
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { showFromPicker = true }) {
                    Text("Desde: ${formatDate(customFromMs ?: nowMs() - 24 * MS_PER_HOUR)}")
                }
                TextButton(onClick = { showToPicker = true }) {
                    Text("Hasta: ${formatDate(customToMs ?: nowMs())}")
                }
            }
        }
    }

    if (showFromPicker) {
        DatePickerSheet(
            initialMs = customFromMs ?: (nowMs() - 24 * MS_PER_HOUR),
            onDismiss = { showFromPicker = false },
            onConfirm = { picked ->
                onCustomChange(picked, customToMs)
                showFromPicker = false
            },
        )
    }
    if (showToPicker) {
        DatePickerSheet(
            initialMs = customToMs ?: nowMs(),
            onDismiss = { showToPicker = false },
            onConfirm = { picked ->
                onCustomChange(customFromMs, picked)
                showToPicker = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerSheet(
    initialMs: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initialMs)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { state.selectedDateMillis?.let(onConfirm) ?: onDismiss() }) {
                Text("Aceptar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    ) {
        DatePicker(state = state)
    }
}

private fun formatDate(ms: Long): String {
    // Cheap YYYY-MM-DD formatter without kotlinx-datetime. Good enough for
    // the picker label; the chart will show its own axis labels later.
    val days = ms / MS_PER_DAY
    // Days since epoch -> Y-M-D via the Gregorian calendar with the same
    // shift everyone else uses (1970-01-01 is day 0).
    // We sidestep leap-year math by trusting that the picker's date is
    // accurate; here we just want a stable, readable label.
    return epochDayToYmd(days)
}

private fun epochDayToYmd(epochDay: Long): String {
    // Algorithm from https://howardhinnant.github.io/date_algorithms.html
    val z = epochDay + 719_468
    val era = if (z >= 0) z / 146_097 else (z - 146_096) / 146_097
    val doe = (z - era * 146_097).toLong()
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
    val y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    val year = if (m <= 2) y + 1 else y
    return "$year-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
}
