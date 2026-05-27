package com.astralink.terralink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.astralink.terralink.ble.BleError
import com.astralink.terralink.ble.protocol.Reading
import com.astralink.terralink.ble.session.ActiveSession
import com.astralink.terralink.ble.session.DownloadProgress
import com.astralink.terralink.model.SavedStation
import com.astralink.terralink.state.ReadingsRepository
import com.astralink.terralink.state.StationsRepository
import com.astralink.terralink.ui.charts.ChartSeries
import com.astralink.terralink.ui.charts.LineChart
import com.astralink.terralink.ui.charts.MoistureDepthColors
import com.astralink.terralink.ui.charts.TemperatureDepthColors
import com.astralink.terralink.ui.components.TimeRangePicker
import com.astralink.terralink.ui.components.TimeRangePreset
import com.astralink.terralink.ui.components.resolveTimeRange
import com.astralink.terralink.util.nowMs
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private const val MAX_ROWS_PER_SYNC = 2_000
private const val SYNC_TIMEOUT_MS = 60_000L

private sealed class SyncPhase {
    data object Idle : SyncPhase()
    data object SyncingTime : SyncPhase()
    data class Downloading(val received: Int, val total: Int) : SyncPhase()
    data object Persisting : SyncPhase()
    data class Done(val readings: List<Reading>, val more: Boolean) : SyncPhase()
    data class Failed(val message: String) : SyncPhase()
}

private enum class KindFilter(val label: String, val sensorKind: String) {
    Moisture("Humedad", "soil_moisture"),
    Temperature("Temperatura", "soil_temperature"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(
    station: SavedStation,
    active: ActiveSession,
    onBack: () -> Unit,
) {
    var preset by remember { mutableStateOf(TimeRangePreset.Last24h) }
    var customFromMs by remember { mutableStateOf<Long?>(null) }
    var customToMs by remember { mutableStateOf<Long?>(null) }
    var phase by remember { mutableStateOf<SyncPhase>(SyncPhase.Idle) }
    var kindFilter by remember { mutableStateOf(KindFilter.Moisture) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sincronizar datos", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { TextButton(onClick = onBack) { Text("Atrás") } },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StationHeaderCard(station = station)

            SectionCard(title = "Rango de datos") {
                TimeRangePicker(
                    preset = preset,
                    customFromMs = customFromMs,
                    customToMs = customToMs,
                    onPresetChange = { preset = it },
                    onCustomChange = { from, to ->
                        customFromMs = from
                        customToMs = to
                    },
                )
            }

            SectionCard(title = "Estado de la sincronización") {
                PhaseStepper(phase = phase)
                Spacer(Modifier.height(12.dp))
                StartButton(
                    phase = phase,
                    onStart = {
                        val range = resolveTimeRange(
                            preset = preset,
                            customFromMs = customFromMs,
                            customToMs = customToMs,
                            lastSyncMs = station.lastSyncMs,
                        )
                        scope.launch {
                            runStreamingSync(
                                active = active,
                                station = station,
                                fromMs = range.fromMs,
                                toMs = range.toMs,
                                onPhase = { phase = it },
                            )
                        }
                    },
                )
                Spacer(Modifier.height(8.dp))
                PhaseStatusLine(phase = phase)
            }

            val done = phase as? SyncPhase.Done
            if (done != null) {
                SectionCard(title = "Visualización") {
                    KindFilterRow(selected = kindFilter, onChange = { kindFilter = it })
                    Spacer(Modifier.height(12.dp))
                    LineChart(
                        series = readingsToSeries(done.readings, kindFilter),
                        yLabel = if (kindFilter == KindFilter.Moisture) "%" else "°C",
                    )
                }
            }
        }
    }
}

// --- Section + station header -----------------------------------------------

@Composable
private fun StationHeaderCard(station: SavedStation) {
    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "Estación",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = station.displayName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = station.bleId,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

// --- Phase stepper + status ---------------------------------------------------

@Composable
private fun PhaseStepper(phase: SyncPhase) {
    val steps = listOf("Hora", "Descarga", "Procesamiento")
    val currentIdx = when (phase) {
        SyncPhase.Idle -> -1
        SyncPhase.SyncingTime -> 0
        is SyncPhase.Downloading -> 1
        SyncPhase.Persisting -> 2
        is SyncPhase.Done -> 3
        is SyncPhase.Failed -> -1
    }
    val isFailed = phase is SyncPhase.Failed
    Row(verticalAlignment = Alignment.CenterVertically) {
        steps.forEachIndexed { i, label ->
            StepDot(
                active = i == currentIdx,
                done = i < currentIdx,
                failed = isFailed && i <= 1,
                label = label,
            )
            if (i < steps.size - 1) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .height(2.dp)
                        .weight(1f)
                        .background(
                            color = if (i < currentIdx)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                        ),
                )
            }
        }
    }
}

@Composable
private fun StepDot(active: Boolean, done: Boolean, failed: Boolean, label: String) {
    val color = when {
        failed -> MaterialTheme.colorScheme.error
        done -> MaterialTheme.colorScheme.primary
        active -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(if (active) 18.dp else 14.dp)
                .background(color, CircleShape),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PhaseStatusLine(phase: SyncPhase) {
    when (phase) {
        SyncPhase.Idle -> Text(
            "Listo para sincronizar.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SyncPhase.SyncingTime -> Text(
            "Enviando hora del móvil al sensor...",
            style = MaterialTheme.typography.bodySmall,
        )
        is SyncPhase.Downloading -> Column {
            Text(
                "Descargando: ${phase.received} / ${phase.total} chunks " +
                    "(${(phase.fractionOrZero() * 100).toInt()} %)",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { phase.fractionOrZero() },
                modifier = Modifier.fillMaxWidth().height(6.dp),
            )
        }
        SyncPhase.Persisting -> Text(
            "Guardando en el almacén local...",
            style = MaterialTheme.typography.bodySmall,
        )
        is SyncPhase.Done -> Text(
            buildString {
                append("Listo: ${phase.readings.size} lecturas descargadas")
                if (phase.more) append(" — hay más datos, repite para continuar")
                else append(".")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        is SyncPhase.Failed -> Text(
            "Error: ${phase.message}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

private fun SyncPhase.Downloading.fractionOrZero(): Float =
    if (total > 0) received.toFloat() / total else 0f

// --- Start button -----------------------------------------------------------

@Composable
private fun StartButton(phase: SyncPhase, onStart: () -> Unit) {
    val running = phase !is SyncPhase.Idle &&
                  phase !is SyncPhase.Done &&
                  phase !is SyncPhase.Failed
    Button(
        onClick = onStart,
        enabled = !running,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = when (phase) {
                SyncPhase.Idle, is SyncPhase.Done, is SyncPhase.Failed -> "Empezar sincronización"
                SyncPhase.SyncingTime, is SyncPhase.Downloading, SyncPhase.Persisting ->
                    "En progreso..."
            },
        )
    }
}

// --- Streaming sync logic ---------------------------------------------------

private suspend fun runStreamingSync(
    active: ActiveSession,
    station: SavedStation,
    fromMs: Long,
    toMs: Long,
    onPhase: (SyncPhase) -> Unit,
) {
    try {
        onPhase(SyncPhase.SyncingTime)
        active.setTime(nowMs())

        onPhase(SyncPhase.Downloading(received = 0, total = 0))
        var collected: List<Reading> = emptyList()
        withTimeout(SYNC_TIMEOUT_MS) {
            active.requestRawReadingsFlow(
                fromMs = fromMs, toMs = toMs, limit = MAX_ROWS_PER_SYNC,
            ).collect { ev ->
                when (ev) {
                    is DownloadProgress.Chunk ->
                        onPhase(SyncPhase.Downloading(ev.received, ev.total))
                    is DownloadProgress.Complete -> {
                        collected = ev.readings
                    }
                }
            }
        }

        onPhase(SyncPhase.Persisting)
        if (collected.isNotEmpty()) {
            ReadingsRepository.insertBatch(station.bleId, collected)
        }
        val nextCursor = collected.lastOrNull()?.tsMs?.plus(1) ?: toMs
        StationsRepository.updateLastSync(station.bleId, nextCursor)

        onPhase(
            SyncPhase.Done(
                readings = collected,
                more = collected.size >= MAX_ROWS_PER_SYNC,
            ),
        )
    } catch (e: BleError) {
        onPhase(SyncPhase.Failed(e.message ?: e::class.simpleName ?: "sync failed"))
    } catch (e: Throwable) {
        onPhase(SyncPhase.Failed(e.message ?: e::class.simpleName ?: "unexpected error"))
    }
}

// --- Chart wiring -----------------------------------------------------------

@Composable
private fun KindFilterRow(selected: KindFilter, onChange: (KindFilter) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KindFilter.entries.forEach { k ->
            FilterChip(
                selected = selected == k,
                onClick = { onChange(k) },
                label = { Text(k.label) },
            )
        }
    }
}

private fun readingsToSeries(
    readings: List<Reading>,
    filter: KindFilter,
): List<ChartSeries> {
    val palette = when (filter) {
        KindFilter.Moisture -> MoistureDepthColors
        KindFilter.Temperature -> TemperatureDepthColors
    }
    val filtered = readings.filter { it.kind == filter.sensorKind && it.depthCm != null }
    val grouped: Map<Int, List<Reading>> = filtered.groupBy { it.depthCm!! }
    val sortedDepths = grouped.keys.sorted()
    return sortedDepths.mapIndexed { idx, depth ->
        val rows = grouped[depth].orEmpty()
        ChartSeries(
            label = "${depth} cm",
            color = palette.getOrElse(idx) { palette.last() },
            points = rows.sortedBy { it.tsMs }.map { row -> row.tsMs to row.value.toFloat() },
        )
    }
}

