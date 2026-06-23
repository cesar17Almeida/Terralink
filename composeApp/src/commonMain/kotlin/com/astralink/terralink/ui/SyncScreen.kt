package com.astralink.terralink.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.astralink.terralink.export.toPngBytes
import com.astralink.terralink.model.SavedStation
import com.astralink.terralink.state.ReadingsRepository
import com.astralink.terralink.state.StationsRepository
import com.astralink.terralink.ui.charts.ChartSeries
import com.astralink.terralink.ui.charts.LineChart
import com.astralink.terralink.ui.charts.MoistureDepthColors
import com.astralink.terralink.ui.charts.TemperatureDepthColors
import com.astralink.terralink.ui.components.BackIconButton
import com.astralink.terralink.ui.components.TerraDialog
import com.astralink.terralink.ui.components.TerraIcons
import com.astralink.terralink.ui.components.TimeRangePreset
import com.astralink.terralink.ui.components.TimeRangeSheet
import com.astralink.terralink.ui.components.resolveTimeRange
import com.astralink.terralink.ui.components.timeRangeLabel
import com.astralink.terralink.util.nowMs
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

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
    AirTemp("Temp. aire", "air_temperature"),   // TA (la del LSTM)
    SoilTemp("Temp. suelo", "soil_temperature"),
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
    var tablePage by remember { mutableStateOf(0) }
    var activeJob by remember { mutableStateOf<Job?>(null) }
    var showCancelDialog by remember { mutableStateOf(false) }
    var showRangeSheet by remember { mutableStateOf(false) }
    var showShareSheet by remember { mutableStateOf(false) }
    val graphicsLayer = rememberGraphicsLayer()
    val exporter = rememberExporter()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    // The visualization is fed from the local cache, so previously-synced data
    // shows up the moment the screen opens -- no need to re-download. A sync just
    // tops up the cache and we reload from it (deduped) when it finishes.
    var displayReadings by remember { mutableStateOf<List<Reading>>(emptyList()) }
    suspend fun reloadCached() {
        val range = resolveTimeRange(preset, customFromMs, customToMs, station.lastSyncMs)
        displayReadings = ReadingsRepository.selectByRange(station.bleId, range.fromMs, range.toMs)
    }
    LaunchedEffect(station.bleId, preset, customFromMs, customToMs) { reloadCached() }

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
        TerraDialog(
            onDismiss = { showCancelDialog = false },
            title = "Cancelar sincronización",
            confirmText = "Cancelar descarga",
            destructive = true,
            onConfirm = {
                activeJob?.cancel()
                showCancelDialog = false
                onBack()
            },
            dismissText = "Continuar descarga",
        ) {
            Text(
                "Hay una descarga en curso. Si sales ahora se cancelará y " +
                    "perderás los datos que aún no se hayan guardado.",
            )
        }
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

            // Compact range: a one-line summary + calendar icon that opens the
            // preset/custom picker as a modal, so the row stays small.
            SectionCard(title = "Rango de datos") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = timeRangeLabel(preset, customFromMs, customToMs),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { showRangeSheet = true }) {
                        Icon(
                            imageVector = TerraIcons.CalendarToday,
                            contentDescription = "Cambiar rango",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            if (showRangeSheet) {
                TimeRangeSheet(
                    preset = preset,
                    customFromMs = customFromMs,
                    customToMs = customToMs,
                    onPresetChange = { preset = it },
                    onCustomChange = { from, to -> customFromMs = from; customToMs = to },
                    onDismiss = { showRangeSheet = false },
                )
            }

            SectionCard(title = "Sincronización") {
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
                            reloadCached()   // pull the freshly-persisted (deduped) rows into the view
                        }
                    },
                )
                // The step tracker stays hidden until a sync starts, then slides
                // in softly; it lingers on Done/Failed to show the outcome.
                AnimatedVisibility(
                    visible = phase !is SyncPhase.Idle,
                    enter = fadeIn(tween(300)) + expandVertically(tween(300)),
                    exit = fadeOut(tween(200)) + shrinkVertically(tween(200)),
                ) {
                    Column {
                        Spacer(Modifier.height(20.dp))
                        PhaseStepper(phase = phase)
                        Spacer(Modifier.height(12.dp))
                        PhaseStatusLine(phase = phase)
                    }
                }
            }

            if (displayReadings.isNotEmpty()) {
                SectionCard(title = "Visualización") {
                    // Header: title + share affordance sitting above the chart.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Gráfico y tabla",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { showShareSheet = true }) {
                            Icon(
                                imageVector = TerraIcons.Share,
                                contentDescription = "Compartir",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    KindFilterDropdown(selected = kindFilter, onChange = { kindFilter = it; tablePage = 0 })
                    Spacer(Modifier.height(12.dp))
                    // Soft crossfade between kinds for chart + table.
                    AnimatedContent(
                        targetState = kindFilter,
                        transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(250)) },
                        label = "kind-crossfade",
                    ) { kind ->
                        Column {
                            // Capture layer wraps the chart so it can be exported as PNG.
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface)
                                    .drawWithContent {
                                        graphicsLayer.record { this@drawWithContent.drawContent() }
                                        drawLayer(graphicsLayer)
                                    },
                            ) {
                                LineChart(
                                    series = readingsToSeries(displayReadings, kind),
                                    yLabel = if (kind == KindFilter.Moisture) "%" else "°C",
                                )
                            }
                            Spacer(Modifier.height(16.dp))
                            DataTable(
                                readings = displayReadings,
                                filter = kind,
                                page = tablePage,
                                onPageChange = { tablePage = it },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showShareSheet) {
        ShareSheet(
            hasReadings = displayReadings.isNotEmpty(),
            jsonText = { readingsToJson(displayReadings) },
            onExportJson = {
                exporter.shareText(
                    readingsToJson(displayReadings),
                    exportFileName(station.bleId, "json", nowMs()), JSON_MIME,
                )
            },
            onExportCsv = {
                exporter.shareText(
                    readingsToCsv(displayReadings),
                    exportFileName(station.bleId, "csv", nowMs()), CSV_MIME,
                )
            },
            onExportImage = {
                scope.launch {
                    val bmp = graphicsLayer.toImageBitmap()
                    exporter.shareBytes(
                        bmp.toPngBytes(),
                        exportFileName(station.bleId, "png", nowMs()), "image/png",
                    )
                }
            },
            onDismiss = { showShareSheet = false },
        )
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
                val lineColor by animateColorAsState(
                    targetValue = if (i < currentIdx) MaterialTheme.colorScheme.primary
                                  else MaterialTheme.colorScheme.outlineVariant,
                    animationSpec = tween(350),
                    label = "line-color",
                )
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .height(2.dp)
                        .weight(1f)
                        .background(lineColor),
                )
            }
        }
    }
}

@Composable
private fun StepDot(active: Boolean, done: Boolean, failed: Boolean, label: String) {
    val targetColor = when {
        failed -> MaterialTheme.colorScheme.error
        done || active -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    // Ease color + size between states so advancing feels smooth, not abrupt.
    val color by animateColorAsState(targetColor, tween(350), label = "dot-color")
    val dotSize by animateDpAsState(if (active) 22.dp else 16.dp, tween(350), label = "dot-size")
    val onPrimary = MaterialTheme.colorScheme.onPrimary

    // Expanding halo while active -- a gentle "we're working" pulse even when the
    // progress bar isn't moving yet (e.g. Counting before COUNT returns).
    val infinite = rememberInfiniteTransition(label = "step-pulse")
    val pulse = if (active) infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "step-pulse-v",
    ).value else 0f

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = Modifier.size(34.dp), contentAlignment = Alignment.Center) {
            if (active) {
                Box(
                    modifier = Modifier
                        .size(22.dp + 12.dp * pulse)
                        .alpha((1f - pulse) * 0.45f)
                        .background(color, CircleShape),
                )
            }
            Box(
                modifier = Modifier.size(dotSize).background(color, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    done -> Text(
                        text = "✓",
                        style = MaterialTheme.typography.labelSmall,
                        color = onPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                    active -> Box(Modifier.size(6.dp).background(onPrimary, CircleShape))
                    else -> Unit
                }
            }
        }
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

// --- Share sheet ------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareSheet(
    hasReadings: Boolean,
    jsonText: () -> String?,
    onExportJson: () -> Unit,
    onExportCsv: () -> Unit,
    onExportImage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    fun close() {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) onDismiss()
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = "Compartir datos",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
            )
            ShareRow("Copiar JSON al portapapeles", "Pega las lecturas donde quieras", hasReadings) {
                jsonText()?.let { clipboard.setText(AnnotatedString(it)) }
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                close()
            }
            ShareRow("Exportar JSON", "Comparte un archivo .json", hasReadings) {
                onExportJson(); close()
            }
            ShareRow("Exportar CSV", "Comparte un archivo .csv", hasReadings) {
                onExportCsv(); close()
            }
            ShareRow("Exportar imagen del gráfico", "Comparte el gráfico actual como PNG", hasReadings) {
                onExportImage(); close()
            }
        }
    }
}

@Composable
private fun ShareRow(title: String, subtitle: String, enabled: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .alpha(if (enabled) 1f else 0.4f),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// --- Chart wiring -----------------------------------------------------------

@Composable
private fun KindFilterDropdown(selected: KindFilter, onChange: (KindFilter) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")

    Box {
        // Anchor: a slim outlined row showing the current selection.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                .clickable { expanded = true }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KindGlyph(kind = selected)
            Spacer(Modifier.width(12.dp))
            Text(
                text = selected.label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = TerraIcons.ExpandMore,
                contentDescription = "Cambiar tipo de dato",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(chevron),
            )
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            KindFilter.entries.forEach { k ->
                DropdownMenuItem(
                    text = { Text(k.label) },
                    onClick = { onChange(k); expanded = false },
                    leadingIcon = { KindGlyph(kind = k) },
                    trailingIcon = if (k == selected) {
                        {
                            Icon(
                                imageVector = TerraIcons.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    } else null,
                )
            }
        }
    }
}

@Composable
private fun KindGlyph(kind: KindFilter) {
    val icon = when (kind) {
        KindFilter.Moisture -> TerraIcons.WaterDrop
        KindFilter.AirTemp -> TerraIcons.Thermostat
        KindFilter.SoilTemp -> TerraIcons.Layers
    }
    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
}

// --- Paginated data table ---------------------------------------------------
// The screen is inside a verticalScroll, so a LazyColumn would crash on the
// infinite-height constraint; pagination lets us render only the current page's
// rows as plain Rows, which is exactly what a table viewer needs anyway.

private const val TABLE_PAGE_SIZE = 12

private enum class SortColumn { Date, Depth, Value }

@Composable
private fun DataTable(
    readings: List<Reading>,
    filter: KindFilter,
    page: Int,
    onPageChange: (Int) -> Unit,
) {
    // Tap a header to sort by it; tap again to flip direction. Date starts
    // newest-first; the other columns start ascending.
    var sortColumn by remember { mutableStateOf(SortColumn.Date) }
    var sortAsc by remember { mutableStateOf(false) }
    val onSort: (SortColumn) -> Unit = { col ->
        if (sortColumn == col) sortAsc = !sortAsc
        else { sortColumn = col; sortAsc = col != SortColumn.Date }
        onPageChange(0)
    }
    val rows = remember(readings, filter, sortColumn, sortAsc) {
        val base = readings.filter { it.kind == filter.sensorKind }
        val cmp: Comparator<Reading> = when (sortColumn) {
            SortColumn.Date -> compareBy { it.tsMs }
            SortColumn.Depth -> compareBy { it.depthCm ?: Int.MIN_VALUE }
            SortColumn.Value -> compareBy { it.value }
        }
        if (sortAsc) base.sortedWith(cmp) else base.sortedWith(cmp.reversed())
    }
    if (rows.isEmpty()) {
        Text(
            "Sin lecturas para esta serie.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val pageCount = (rows.size + TABLE_PAGE_SIZE - 1) / TABLE_PAGE_SIZE
    val safePage = page.coerceIn(0, pageCount - 1)
    val start = safePage * TABLE_PAGE_SIZE
    val pageRows = rows.subList(start, minOf(start + TABLE_PAGE_SIZE, rows.size))
    val depthHeader = if (filter == KindFilter.AirTemp) "Sensor" else "Prof."

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            SortHeaderCell("Fecha (UTC)", 0.46f, SortColumn.Date, sortColumn, sortAsc) { onSort(SortColumn.Date) }
            SortHeaderCell(depthHeader, 0.27f, SortColumn.Depth, sortColumn, sortAsc) { onSort(SortColumn.Depth) }
            SortHeaderCell("Valor", 0.27f, SortColumn.Value, sortColumn, sortAsc, end = true) { onSort(SortColumn.Value) }
        }
        HorizontalDivider()
        pageRows.forEach { r ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                TableCell(formatDateTime(r.tsMs), 0.46f)
                TableCell(rowKindLabel(r, filter), 0.27f)
                TableCell(formatValue(r, filter), 0.27f, end = true)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { onPageChange(safePage - 1) }, enabled = safePage > 0) {
                Text("‹ Anterior")
            }
            Text(
                "Página ${safePage + 1} / $pageCount · ${rows.size} filas",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { onPageChange(safePage + 1) }, enabled = safePage < pageCount - 1) {
                Text("Siguiente ›")
            }
        }
    }
}

@Composable
private fun RowScope.TableCell(text: String, weight: Float, header: Boolean = false, end: Boolean = false) {
    Text(
        text = text,
        modifier = Modifier.weight(weight),
        style = MaterialTheme.typography.labelSmall,
        fontFamily = if (header) FontFamily.Default else FontFamily.Monospace,
        fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
        textAlign = if (end) TextAlign.End else TextAlign.Start,
        color = if (header) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun RowScope.SortHeaderCell(
    label: String,
    weight: Float,
    column: SortColumn,
    activeColumn: SortColumn,
    ascending: Boolean,
    end: Boolean = false,
    onClick: () -> Unit,
) {
    val active = column == activeColumn
    val arrow = if (!active) "" else if (ascending) " ↑" else " ↓"
    Text(
        text = label + arrow,
        modifier = Modifier.weight(weight).clickable(onClick = onClick),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        textAlign = if (end) TextAlign.End else TextAlign.Start,
        color = if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun rowKindLabel(r: Reading, filter: KindFilter): String = when (filter) {
    KindFilter.AirTemp -> "aire"
    else -> "${r.depthCm ?: 0} cm"
}

private fun formatValue(r: Reading, filter: KindFilter): String =
    if (filter == KindFilter.Moisture) fmtDecimals(r.value, 2) else "${fmtDecimals(r.value, 1)}°"

// Fixed-decimals formatter without depending on platform String.format.
private fun fmtDecimals(v: Double, places: Int): String {
    var mul = 1L
    repeat(places) { mul *= 10 }
    val neg = v < 0
    val scaled = kotlin.math.round(kotlin.math.abs(v) * mul).toLong()
    val whole = scaled / mul
    val frac = scaled % mul
    val sign = if (neg && (whole != 0L || frac != 0L)) "-" else ""
    return if (places == 0) "$sign$whole"
           else "$sign$whole.${frac.toString().padStart(places, '0')}"
}

private const val MS_PER_DAY_TABLE = 86_400_000L

// epoch ms (UTC) -> "MM-DD HH:mm" via the Howard Hinnant civil-from-days algorithm.
private fun formatDateTime(ms: Long): String {
    val epochDay = ms / MS_PER_DAY_TABLE
    val msOfDay = ms % MS_PER_DAY_TABLE
    val hh = (msOfDay / 3_600_000L).toInt()
    val mm = ((msOfDay / 60_000L) % 60).toInt()
    val z = epochDay + 719_468
    val era = if (z >= 0) z / 146_097 else (z - 146_096) / 146_097
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val mon = if (mp < 10) mp + 3 else mp - 9
    fun p2(n: Long) = n.toString().padStart(2, '0')
    return "${p2(mon)}-${p2(d)} ${p2(hh.toLong())}:${p2(mm.toLong())}"
}

private fun readingsToSeries(
    readings: List<Reading>,
    filter: KindFilter,
): List<ChartSeries> {
    val palette = when (filter) {
        KindFilter.Moisture -> MoistureDepthColors
        KindFilter.AirTemp, KindFilter.SoilTemp -> TemperatureDepthColors
    }
    val filtered = readings.filter { it.kind == filter.sensorKind }

    // Air temperature has no depth -> a single series.
    if (filter == KindFilter.AirTemp) {
        if (filtered.isEmpty()) return emptyList()
        return listOf(ChartSeries(
            label = "Aire",
            color = palette.first(),
            points = filtered.sortedBy { it.tsMs }.map { it.tsMs to it.value.toFloat() },
        ))
    }

    val grouped: Map<Int, List<Reading>> = filtered.filter { it.depthCm != null }.groupBy { it.depthCm!! }
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

