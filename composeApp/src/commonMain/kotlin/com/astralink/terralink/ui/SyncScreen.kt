package com.astralink.terralink.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.astralink.terralink.ble.BleError
import com.astralink.terralink.ble.protocol.Reading
import com.astralink.terralink.ble.session.ActiveSession
import com.astralink.terralink.ble.session.DownloadProgress
import com.astralink.terralink.export.CSV_MIME
import com.astralink.terralink.export.JSON_MIME
import com.astralink.terralink.export.exportFileName
import com.astralink.terralink.export.readingsToCsv
import com.astralink.terralink.export.readingsToJson
import com.astralink.terralink.export.rememberExporter
import com.astralink.terralink.model.SavedStation
import com.astralink.terralink.state.ReadingsRepository
import com.astralink.terralink.state.StationsRepository
import com.astralink.terralink.ui.charts.ChartSeries
import com.astralink.terralink.ui.charts.LineChart
import com.astralink.terralink.ui.charts.MoistureDepthColors
import com.astralink.terralink.ui.charts.TemperatureDepthColors
import com.astralink.terralink.ui.components.BackIconButton
import com.astralink.terralink.ui.components.TimeRangePicker
import com.astralink.terralink.ui.components.TimeRangePreset
import com.astralink.terralink.ui.components.resolveTimeRange
import com.astralink.terralink.util.nowMs
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import androidx.compose.material3.AlertDialog

// Per-page hard cap to keep each notify stream under our per-page timeout.
// We page automatically until the Pi returns less than this, so the user
// always gets the full requested range without re-pulsing.
private const val PAGE_SIZE = 500
private const val PAGE_TIMEOUT_MS = 30_000L
// Safety net: stop after this many pages even if the Pi keeps returning
// full pages. 200 * 500 = 100k readings, ~13 hours of mock data at 12 rows
// / 30s; plenty of margin and protects against pathological loops.
private const val MAX_PAGES = 200

private sealed class SyncPhase {
    data object Idle : SyncPhase()
    data object SyncingTime : SyncPhase()
    data object Counting : SyncPhase()
    data class Downloading(
        val page: Int,
        val pagesTotal: Int,           // 0 means "unknown" (count failed)
        val expectedTotal: Long,       // 0 means "unknown"
        val totalSoFar: Int,
        val received: Int,
        val total: Int,
    ) : SyncPhase()
    data object Persisting : SyncPhase()
    data class Done(val readings: List<Reading>) : SyncPhase()
    data class Failed(val message: String) : SyncPhase()
}

private fun SyncPhase.isActive(): Boolean = this is SyncPhase.SyncingTime ||
    this is SyncPhase.Counting ||
    this is SyncPhase.Downloading ||
    this is SyncPhase.Persisting

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
    var activeJob by remember { mutableStateOf<Job?>(null) }
    var showCancelDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    // Subtle haptic ticks at phase transitions. We key on the page index so
    // we don't fire on every chunk (would feel like a buzz). Done and Failed
    // get their own one-shot patterns.
    val currentPage = (phase as? SyncPhase.Downloading)?.page ?: 0
    LaunchedEffect(currentPage) {
        if (currentPage > 0) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }
    LaunchedEffect(phase::class) {
        when (phase) {
            is SyncPhase.Done -> haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            is SyncPhase.Failed -> haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            else -> Unit
        }
    }

    // Intercept back while a sync is running so the user can confirm before
    // losing in-flight progress.
    val safeBack: () -> Unit = {
        if (phase.isActive()) showCancelDialog = true else onBack()
    }

    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title = { Text("Cancelar sincronización") },
            text = {
                Text(
                    "Hay una descarga en curso. Si sales ahora se cancelará y " +
                        "perderás los datos que aún no se hayan guardado.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    activeJob?.cancel()
                    showCancelDialog = false
                    onBack()
                }) { Text("Cancelar descarga") }
            },
            dismissButton = {
                TextButton(onClick = { showCancelDialog = false }) {
                    Text("Continuar descarga")
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sincronizar datos", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { BackIconButton(onClick = safeBack) },
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
                        activeJob = scope.launch {
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

                ExportSection(
                    station = station,
                    readings = done.readings,
                )
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
        SyncPhase.Counting -> 1
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
    // While the step is active we pulse the dot's alpha so the user has a
    // gentle "we're working" signal even when the bottom progress bar isn't
    // visibly moving yet (e.g. during Counting before the COUNT returns).
    val infinite = rememberInfiniteTransition(label = "step-blink")
    val alpha = if (active) {
        infinite.animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 700, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "step-alpha",
        ).value
    } else 1f
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(if (active) 18.dp else 14.dp)
                .alpha(alpha)
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
        SyncPhase.Counting -> Text(
            "Calculando total de lecturas...",
            style = MaterialTheme.typography.bodySmall,
        )
        is SyncPhase.Downloading -> Column {
            val knownTotal = phase.expectedTotal > 0 && phase.pagesTotal > 0
            Text(
                text = if (knownTotal) {
                    val pct = (phase.globalFraction() * 100).toInt()
                    "Página ${phase.page} / ${phase.pagesTotal} · " +
                        "${phase.totalSoFar} / ${phase.expectedTotal} lecturas · $pct %"
                } else {
                    "Página ${phase.page} · ${phase.totalSoFar} lecturas hasta ahora · " +
                        "${(phase.fractionOrZero() * 100).toInt()} %"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = {
                    if (knownTotal) phase.globalFraction() else phase.fractionOrZero()
                },
                modifier = Modifier.fillMaxWidth().height(6.dp),
            )
        }
        SyncPhase.Persisting -> Text(
            "Guardando en el almacén local...",
            style = MaterialTheme.typography.bodySmall,
        )
        is SyncPhase.Done -> Text(
            text = "Listo: ${phase.readings.size} lecturas descargadas.",
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

private fun SyncPhase.Downloading.globalFraction(): Float {
    if (expectedTotal <= 0) return fractionOrZero()
    // Bytes received in the current page contribute their own fraction so the
    // bar moves continuously between page boundaries, not in 1/M jumps.
    val partial = if (total > 0) received.toFloat() / total else 0f
    val approx = totalSoFar + partial * (expectedTotal.toFloat() / pagesTotal.coerceAtLeast(1))
    return (approx / expectedTotal).coerceIn(0f, 1f)
}

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
                SyncPhase.SyncingTime, SyncPhase.Counting,
                is SyncPhase.Downloading, SyncPhase.Persisting ->
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

        // First ask the Pi how many rows the GET would return so we can show
        // "Página N / M" with a real denominator. If COUNT fails (e.g. Pi on
        // an older firmware), fall back to the unknown-total path.
        onPhase(SyncPhase.Counting)
        val expectedTotal: Long = try {
            active.requestRawCount(fromMs = fromMs, toMs = toMs)
        } catch (e: Throwable) {
            0L
        }
        val pagesTotal: Int =
            if (expectedTotal > 0) ((expectedTotal + PAGE_SIZE - 1) / PAGE_SIZE).toInt()
            else 0

        // Paginated download: loop until the Pi returns a non-full page
        // or yields nothing. The user picked a date range and expects ALL
        // of it; the app handles batching internally so they never see
        // "hay más, repite". PAGE_SIZE keeps each request below our
        // per-page timeout (~5-10s of notify traffic).
        val all = mutableListOf<Reading>()
        var nextFrom = fromMs
        var page = 0
        while (page < MAX_PAGES) {
            page++
            onPhase(SyncPhase.Downloading(
                page = page, pagesTotal = pagesTotal, expectedTotal = expectedTotal,
                totalSoFar = all.size, received = 0, total = 0,
            ))
            var pageReadings: List<Reading> = emptyList()
            withTimeout(PAGE_TIMEOUT_MS) {
                active.requestRawReadingsFlow(
                    fromMs = nextFrom, toMs = toMs, limit = PAGE_SIZE,
                ).collect { ev ->
                    when (ev) {
                        is DownloadProgress.Chunk -> onPhase(
                            SyncPhase.Downloading(
                                page = page,
                                pagesTotal = pagesTotal,
                                expectedTotal = expectedTotal,
                                totalSoFar = all.size,
                                received = ev.received,
                                total = ev.total,
                            )
                        )
                        is DownloadProgress.Complete -> pageReadings = ev.readings
                    }
                }
            }
            if (pageReadings.isEmpty()) break
            all += pageReadings
            // Move the cursor past the last reading we got so the next page
            // doesn't re-include it. tsMs ranges are inclusive on `from`.
            nextFrom = (pageReadings.last().tsMs + 1)
            if (pageReadings.size < PAGE_SIZE) break  // server drained
        }

        onPhase(SyncPhase.Persisting)
        if (all.isNotEmpty()) {
            ReadingsRepository.insertBatch(station.bleId, all)
        }
        val nextCursor = all.lastOrNull()?.tsMs?.plus(1) ?: toMs
        StationsRepository.updateLastSync(station.bleId, nextCursor)

        onPhase(SyncPhase.Done(readings = all))
    } catch (e: BleError) {
        onPhase(SyncPhase.Failed(e.message ?: e::class.simpleName ?: "sync failed"))
    } catch (e: Throwable) {
        onPhase(SyncPhase.Failed(e.message ?: e::class.simpleName ?: "unexpected error"))
    }
}

// --- Export ----------------------------------------------------------------

@Composable
private fun ExportSection(
    station: SavedStation,
    readings: List<Reading>,
) {
    val exporter = rememberExporter()
    SectionCard(title = "Exportar lecturas") {
        Text(
            text = "${readings.size} lecturas descargadas en esta sesión.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = {
                    val now = nowMs()
                    exporter.shareText(
                        content = readingsToCsv(readings),
                        fileName = exportFileName(station.bleId, "csv", now),
                        mimeType = CSV_MIME,
                    )
                },
                enabled = readings.isNotEmpty(),
            ) { Text("Exportar CSV") }
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = {
                    val now = nowMs()
                    exporter.shareText(
                        content = readingsToJson(readings),
                        fileName = exportFileName(station.bleId, "json", now),
                        mimeType = JSON_MIME,
                    )
                },
                enabled = readings.isNotEmpty(),
            ) { Text("Exportar JSON") }
        }
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

