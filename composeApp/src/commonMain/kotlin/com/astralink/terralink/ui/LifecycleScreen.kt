// "Ciclo de vida": the station's past and future on one scrollable time axis.
//
// The station keeps no history of itself -- ~48 h of readings, one forecast, a
// 24-line log ring, all in RAM. This screen is the app's answer to that: on every
// connection it harvests what the station can still say about its recent past into
// a local journal, and it computes the station's future from the same config the
// firmware schedules with. Neither half needs a byte of firmware change.
package com.astralink.terralink.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.astralink.terralink.ble.session.ActiveSession
import com.astralink.terralink.model.SavedStation
import com.astralink.terralink.timeline.EventKind
import com.astralink.terralink.timeline.LifecycleLoad
import com.astralink.terralink.timeline.StationEvent
import com.astralink.terralink.timeline.buildSeries
import com.astralink.terralink.timeline.chip
import com.astralink.terralink.timeline.currentStateLine
import com.astralink.terralink.timeline.loadLifecycle
import com.astralink.terralink.timeline.nextWakeAfter
import com.astralink.terralink.timeline.seriesKeysOf
import com.astralink.terralink.ui.components.timeline.DayFooter
import com.astralink.terralink.ui.components.timeline.EventDetailBar
import com.astralink.terralink.ui.components.timeline.LifecycleHeader
import com.astralink.terralink.ui.components.timeline.SeriesChips
import com.astralink.terralink.ui.components.timeline.StateRow
import com.astralink.terralink.ui.components.timeline.eyebrow
import com.astralink.terralink.ui.components.timeline.TimelineTrack
import com.astralink.terralink.ui.components.timeline.TrackControls
import com.astralink.terralink.ui.components.timeline.TrackData
import com.astralink.terralink.ui.components.timeline.TrackMode
import com.astralink.terralink.ui.components.timeline.TrackSeries
import com.astralink.terralink.ui.components.timeline.Zoom
import com.astralink.terralink.ui.components.timeline.dayStats
import com.astralink.terralink.ui.components.timeline.hhmm
import com.astralink.terralink.ui.components.timeline.humanGap
import com.astralink.terralink.ui.components.timeline.rememberTimelineViewport
import com.astralink.terralink.ui.components.timeline.timeTones
import com.astralink.terralink.ui.components.timeline.titleOf

private sealed class LifePhase {
    data object Loading : LifePhase()
    data class Ready(val load: LifecycleLoad) : LifePhase()
    data class Failed(val message: String) : LifePhase()
}

@Composable
fun LifecycleScreen(
    station: SavedStation,
    active: ActiveSession,
    onOpenAccuracy: () -> Unit,
    onBack: () -> Unit,
) {
    var phase by remember { mutableStateOf<LifePhase>(LifePhase.Loading) }
    var reloadKey by remember { mutableStateOf(0) }
    var selectedSeries by remember { mutableStateOf<String?>(null) }
    var selectedEvent by remember { mutableStateOf<StationEvent?>(null) }
    var mode by remember { mutableStateOf(TrackMode.PISTA) }

    LaunchedEffect(reloadKey) {
        phase = LifePhase.Loading
        phase = try {
            LifePhase.Ready(loadLifecycle(active, station.bleId))
        } catch (e: Throwable) {
            LifePhase.Failed(e.message ?: "No se pudo leer el ciclo de la estación")
        }
    }

    Scaffold { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when (val p = phase) {
                LifePhase.Loading -> Centered { CircularProgressIndicator() }
                is LifePhase.Failed -> Centered {
                    Text("No se pudo cargar", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(p.message, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = { reloadKey++ }) { Text("Reintentar") }
                }
                is LifePhase.Ready -> LifecycleContent(
                    stationName = station.displayName,
                    load = p.load,
                    mode = mode,
                    onMode = { mode = it; selectedEvent = null },
                    selectedSeriesId = selectedSeries,
                    onSelectSeries = { selectedSeries = it },
                    selectedEvent = selectedEvent,
                    onSelectEvent = { selectedEvent = it },
                    onOpenAccuracy = onOpenAccuracy,
                    onBack = onBack,
                )
            }
        }
    }
}

@Composable
private fun LifecycleContent(
    stationName: String,
    load: LifecycleLoad,
    mode: TrackMode,
    onMode: (TrackMode) -> Unit,
    selectedSeriesId: String?,
    onSelectSeries: (String) -> Unit,
    selectedEvent: StationEvent?,
    onSelectEvent: (StationEvent?) -> Unit,
    onOpenAccuracy: () -> Unit,
    onBack: () -> Unit,
) {
    val now = load.stationNowMs
    val offset = load.config.utcOffsetMin
    val viewport = rememberTimelineViewport(now, Zoom.DAY)

    val keys = remember(load) { seriesKeysOf(load.readings) }
    val chips = remember(keys) { keys.map { it.chip() } }
    val key = remember(keys, selectedSeriesId) {
        keys.firstOrNull { it.id == selectedSeriesId } ?: keys.firstOrNull()
    }
    val series = remember(key, load) {
        key?.let { buildSeries(it, load.readings, load.forecast, load.archive, now) } ?: TrackSeries()
    }
    val events = remember(load, key) {
        // The track shows the station's whole life, but a sample mark belongs to the
        // channel being drawn: without this, six depths of one sweep stack up as six.
        load.events.filter { it.kind != EventKind.SAMPLE || key == null || it.port == key.port }
    }
    val day = remember(events, now) { events.dayStats(now) }
    val next = remember(events, now) { events.nextWakeAfter(now) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 22.dp)) {
            LifecycleHeader(
                stationName = stationName,
                title = "Ciclo de vida",
                clockLabel = hhmm(now + offset * 60_000L) + " · estación",
                onBack = onBack,
            )
            Spacer(Modifier.height(18.dp))
            SeriesChips(chips, key?.id, onSelectSeries)
            Spacer(Modifier.height(20.dp))
            StateRowSection(load, events, now, next)
            Spacer(Modifier.height(18.dp))
            TrackControls(
                unitLabel = series.unit.ifBlank { "sin unidad" },
                activeZoom = viewport.activeZoom,
                mode = mode,
                onZoom = { viewport.setZoom(it) },
                onMode = onMode,
            )
        }
        Spacer(Modifier.height(12.dp))

        TimelineTrack(
            data = TrackData(events = events, series = series),
            viewport = viewport,
            nowMs = now,
            mode = mode,
            selected = selectedEvent,
            onSelect = onSelectEvent,
        )

        Column(Modifier.padding(horizontal = 22.dp)) {
            DayFooter(
                wakes = day.wakes,
                asleep = "${(day.asleepFraction * 100).toInt()} %",
                lora = "${day.loraOk}/${day.loraTotal}",
                loraHealthy = day.loraOk == day.loraTotal,
            )
            Spacer(Modifier.height(12.dp))
            AccuracyLink(load, onOpenAccuracy)
        }
        Spacer(Modifier.weight(1f))
        EventDetailBar(
            event = selectedEvent,
            nowMs = now,
            utcOffsetMin = offset,
            onClear = { onSelectEvent(null) },
        )
    }
}

@Composable
private fun StateRowSection(
    load: LifecycleLoad,
    events: List<StationEvent>,
    now: Long,
    next: StationEvent?,
) {
    StateRow(
        stateNow = currentStateLine(events, load.config, now),
        nextLabel = "Próximo",
        nextValue = next?.let {
            hhmm(it.tsMs + load.config.utcOffsetMin * 60_000L) +
                " · " + titleOf(it.kind).lowercase() +
                " (en " + humanGap(it.tsMs - now) + ")"
        } ?: "—",
    )
}

@Composable
private fun AccuracyLink(load: LifecycleLoad, onOpen: () -> Unit) {
    val t = timeTones()
    val runs = load.forecastRuns
    Text(
        text = if (runs > 0) "VER PREDICCIÓN VS REAL · $runs ${if (runs == 1L) "PRONÓSTICO" else "PRONÓSTICOS"} ARCHIVADOS"
               else "VER PREDICCIÓN VS REAL",
        modifier = Modifier.clickable(onClick = onOpen),
        style = eyebrow(t.accent, 8.5f),
    )
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { content() }
}
