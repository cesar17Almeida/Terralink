// "Predicción vs real": what the LSTM said would happen, against what the probe
// then measured.
//
// This is only possible because the app archives each forecast at the moment it
// reads it (see ForecastArchive): the station overwrites its forecast on every run,
// so by the time reality catches up with a prediction, the prediction itself is
// gone from the board. Everything on this screen is scored locally, from the
// archive and the readings cache -- no firmware change, no extra BLE message.
package com.astralink.terralink.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astralink.terralink.ble.session.ActiveSession
import com.astralink.terralink.model.SavedStation
import com.astralink.terralink.util.nowMs
import kotlinx.coroutines.launch
import terralink.composeapp.generated.resources.Res
import com.astralink.terralink.timeline.LifecycleLoad
import com.astralink.terralink.timeline.ReplayReport
import com.astralink.terralink.timeline.ReplayStage
import com.astralink.terralink.timeline.ReplayWindow
import com.astralink.terralink.timeline.SeriesKey
import com.astralink.terralink.timeline.parseReplayWindow
import com.astralink.terralink.timeline.runDatasetReplay
import com.astralink.terralink.timeline.buildAccuracySeries
import com.astralink.terralink.timeline.longLabel
import com.astralink.terralink.timeline.loadLifecycle
import com.astralink.terralink.timeline.seriesKeysOf
import com.astralink.terralink.timeline.unitOf
import com.astralink.terralink.ui.components.timeline.ACCURACY_CHART_HEIGHT
import com.astralink.terralink.ui.components.timeline.AccuracyChart
import com.astralink.terralink.ui.components.timeline.AccuracyLegend
import com.astralink.terralink.ui.components.timeline.AccuracyStatsRow
import com.astralink.terralink.ui.components.timeline.HorizonBand
import com.astralink.terralink.ui.components.timeline.HorizonFilter
import com.astralink.terralink.ui.components.timeline.LifecycleHeader
import com.astralink.terralink.ui.components.timeline.PairTable
import com.astralink.terralink.ui.components.timeline.PointReadout
import com.astralink.terralink.ui.components.timeline.ReplayPanel
import com.astralink.terralink.ui.components.timeline.eyebrow
import com.astralink.terralink.ui.components.timeline.hhmm
import com.astralink.terralink.ui.components.timeline.timeTones

private sealed class AccPhase {
    data object Loading : AccPhase()
    data class Ready(val load: LifecycleLoad) : AccPhase()
    data class Failed(val message: String) : AccPhase()
}

@Composable
fun AccuracyScreen(
    station: SavedStation,
    active: ActiveSession,
    onBack: () -> Unit,
) {
    var phase by remember { mutableStateOf<AccPhase>(AccPhase.Loading) }
    var reloadKey by remember { mutableStateOf(0) }
    var replay by remember { mutableStateOf<ReplayWindow?>(null) }
    var replayStage by remember { mutableStateOf<ReplayStage?>(null) }
    var replayReport by remember { mutableStateOf<ReplayReport?>(null) }
    val scope = rememberCoroutineScope()

    // The bundled dataset window. A missing or malformed asset must not take the
    // screen down with it -- it only gates the developer panel.
    LaunchedEffect(Unit) {
        replay = runCatching {
            parseReplayWindow(Res.readBytes("files/lstm_replay_window.csv").decodeToString())
        }.getOrNull()
    }

    LaunchedEffect(reloadKey) {
        phase = AccPhase.Loading
        phase = try {
            AccPhase.Ready(loadLifecycle(active, station.bleId))
        } catch (e: Throwable) {
            AccPhase.Failed(e.message ?: "No se pudo leer la estación")
        }
    }

    Scaffold { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when (val p = phase) {
                AccPhase.Loading -> Centered { CircularProgressIndicator() }
                is AccPhase.Failed -> Centered {
                    Text("No se pudo cargar", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(p.message, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = { reloadKey++ }) { Text("Reintentar") }
                }
                is AccPhase.Ready -> AccuracyContent(
                    stationName = station.displayName,
                    load = p.load,
                    replay = replay,
                    replayStage = replayStage,
                    replayReport = replayReport,
                    onRunReplay = {
                        val w = replay ?: return@AccuracyContent
                        scope.launch {
                            replayReport = null
                            try {
                                replayReport = runDatasetReplay(
                                    active = active,
                                    stationId = station.bleId,
                                    window = w,
                                    nowMs = nowMs(),
                                    onStage = { replayStage = it },
                                )
                                reloadKey++      // pull the archived forecast into the chart
                            } catch (e: Throwable) {
                                val label = (replayStage as? ReplayStage.Step)?.label ?: "el ensayo"
                                replayStage = ReplayStage.Failed(
                                    label, e.message ?: e::class.simpleName ?: "error desconocido",
                                )
                            }
                        }
                    },
                    onBack = onBack,
                )
            }
        }
    }
}

@Composable
private fun AccuracyContent(
    stationName: String,
    load: LifecycleLoad,
    replay: ReplayWindow?,
    replayStage: ReplayStage?,
    replayReport: ReplayReport?,
    onRunReplay: () -> Unit,
    onBack: () -> Unit,
) {
    val t = timeTones()
    val now = load.stationNowMs
    val offset = load.config.utcOffsetMin
    var band by remember { mutableStateOf(HorizonBand.ALL) }
    var scrub by remember { mutableStateOf<Int?>(null) }
    var showTable by remember { mutableStateOf(false) }

    // The LSTM only ever predicts soil moisture at 30 cm, so that is the channel
    // scored here -- picking any other would be scoring the model on a series it was
    // never given.
    val key = remember(load) {
        seriesKeysOf(load.readings).firstOrNull { it.isForecastable }
            ?: SeriesKey(1, com.astralink.terralink.ble.protocol.ReadingKind.SOIL_MOISTURE, 30)
    }
    val series = remember(load, band, key) {
        buildAccuracySeries(
            forecasts = load.archive.filter { it.horizonH in band.range },
            readings = load.readings,
            kind = key.kind,
            depthCm = key.depthCm,
            preferHorizon = null,
        )
    }
    val unit = unitOf(key.kind)
    val selected = scrub?.let { series.pairs.getOrNull(it) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp),
    ) {
        LifecycleHeader(
            stationName = stationName,
            title = "Predicción vs real",
            clockLabel = hhmm(now + offset * 60_000L) + " · estación",
            onBack = onBack,
        )
        Spacer(Modifier.height(12.dp))
        // Name the variable, always. The model forecasts exactly one thing, and a
        // chart of two green lines does not say which.
        SeriesBadge(key.longLabel(), unit)
        Spacer(Modifier.height(12.dp))
        Text(
            "El LSTM predice 24 h de ${key.longLabel().substringBefore(" ·")}. Cada " +
                "pronóstico se archiva al leerlo, porque la estación sólo guarda el " +
                "último; estas son las horas que ya se han medido, frente a lo que el " +
                "modelo había predicho para ellas.",
            modifier = Modifier.widthIn(max = 320.dp),
            style = TextStyle(fontSize = 12.5.sp, lineHeight = 20.sp, color = t.muted),
        )
        Spacer(Modifier.height(18.dp))
        HorizonFilter(band, { band = it; scrub = null })
        Spacer(Modifier.height(18.dp))
        AccuracyStatsRow(series.stats, unit)
        Spacer(Modifier.height(18.dp))
        AccuracyLegend()
        Spacer(Modifier.height(10.dp))
        AccuracyChart(
            pairs = series.pairs,
            utcOffsetMin = offset,
            selectedIndex = scrub,
            onScrub = { scrub = it },
            modifier = Modifier.height(ACCURACY_CHART_HEIGHT),
        )
        Spacer(Modifier.height(12.dp))
        PointReadout(selected, offset, unit)
        Spacer(Modifier.height(20.dp))
        CoverageNote(load, series.pairs.size, series.actualOnly.size)
        Spacer(Modifier.height(18.dp))
        Text(
            if (showTable) "OCULTAR TABLA" else "VER TABLA DE HORAS",
            modifier = Modifier.clickable { showTable = !showTable },
            style = eyebrow(t.accent, 8.5f),
        )
        if (showTable) {
            Spacer(Modifier.height(10.dp))
            PairTable(series.pairs, offset)
        }
        // Dev only: the same flag that reveals the mock tools on Predicciones.
        if (load.config.mockEnabled) {
            Spacer(Modifier.height(24.dp))
            ReplayPanel(
                window = replay,
                stage = replayStage,
                report = replayReport,
                onRun = onRunReplay,
            )
        }
        Spacer(Modifier.height(32.dp))
    }
}

/** Why the numbers are what they are: how much there is to score, and what is
 *  still waiting for reality to arrive. */
/** The subject of the chart, stated once and prominently. */
@Composable
private fun SeriesBadge(label: String, unit: String) {
    val t = timeTones()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .height(26.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(t.accent.copy(alpha = 0.10f))
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label.uppercase(), style = eyebrow(t.accent, 8.5f))
        }
        if (unit.isNotBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(unit, style = eyebrow(t.faint, 8.5f))
        }
    }
}

@Composable
private fun CoverageNote(load: LifecycleLoad, scored: Int, unscored: Int) {
    val t = timeTones()
    val pending = load.forecast.count { it.targetMs > load.stationNowMs }
    val text = when {
        load.forecastRuns == 0L ->
            "Todavía no se ha archivado ningún pronóstico. Se archivan solos cada vez " +
                "que abres esta estación después de que corra el ciclo diario."
        scored == 0 ->
            "Hay ${load.forecastRuns} pronóstico(s) archivado(s), pero ninguna de sus " +
                "horas se ha medido aún. $pending hora(s) siguen por delante."
        else ->
            "$scored hora(s) comparadas sobre ${load.forecastRuns} pronóstico(s) archivado(s). " +
                "$pending por delante; $unscored hora(s) medidas sin predicción que las cubra."
    }
    Text(text, style = TextStyle(fontSize = 12.sp, lineHeight = 19.sp, color = t.faint))
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { content() }
}
