package com.astralink.terralink.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.astralink.terralink.ble.protocol.IngestPoint
import com.astralink.terralink.ble.protocol.Prediction
import com.astralink.terralink.ble.protocol.Reading
import com.astralink.terralink.ble.protocol.ReadingKind
import com.astralink.terralink.ble.session.ActiveSession
import com.astralink.terralink.model.SavedStation
import com.astralink.terralink.ui.components.BackIconButton
import com.astralink.terralink.ui.components.SectionHeader
import com.astralink.terralink.ui.components.Spec
import com.astralink.terralink.ui.components.SpecTable
import com.astralink.terralink.ui.components.TerraDialog
import com.astralink.terralink.util.formatRelativeMs
import com.astralink.terralink.util.nowMs
import kotlinx.coroutines.launch

private const val KIND_HS30_FORECAST = "hs30_forecast"
private const val KIND_RECOMMENDATION = "water_recommendation"

// The LSTM's past window: 48 HOURLY steps per series. What advances it is a new
// hour, not a new reading -- everything inside one clock hour is aggregated into a
// single mean on the station (storage_aggregate_hourly), so readings a minute apart
// are one step, not sixty.
private const val WINDOW_HOURS = 48
private const val HOUR_MS = 3_600_000L

// Real hourly buckets the station requires before it will infer; below this it
// would be back-filling most of the window from a handful of samples. Mirrors the
// firmware's LSTM_MIN_PAST_HOURS (savia/lstm_input.h) -- keep the two in step.
private const val MIN_HOURS = 24

// The firmware refuses to infer unless the hour it is inferring contains a real
// reading (LSTM_MAX_STALE_HOURS = 0), but it guarantees that by sampling right
// before it infers -- so this screen, which looks at an arbitrary moment, must NOT
// mirror that bound or it would cry wolf over the normal gap between captures.
// This is the loose "the probe looks dead" threshold instead: well past any capture
// cadence that could feed an hourly model.
private const val STALE_SOIL_HOURS = 6L

private sealed class PredState {
    data object Loading : PredState()
    data class Loaded(val predictions: List<Prediction>) : PredState()
    data class Failed(val message: String) : PredState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PredictionsScreen(
    station: SavedStation,
    active: ActiveSession,
    onBack: () -> Unit,
) {
    var state by remember { mutableStateOf<PredState>(PredState.Loading) }
    var window48 by remember { mutableStateOf<List<Reading>?>(null) }
    // The LSTM's TA (past + future) comes from the station's weather cache, not from
    // its readings; the status snapshot is the only thing that reports on it.
    var weatherUpdatedMs by remember { mutableStateOf<Long?>(null) }
    var refreshing by remember { mutableStateOf(false) }     // soft reload -> fade overlay
    var loadingLabel by remember { mutableStateOf("Cargando…") }
    var logsDialog by remember { mutableStateOf(false) }     // logs modal (opened from a failure)
    // Dev tools (mock forecast + ingest) hide unless the station has "mock" enabled --
    // the existing per-station developer flag, toggled from ConfigurationScreen.
    var devMode by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // The firmware handles ONE data_request at a time and the notify stream is
    // shared, so reads MUST run sequentially in a single coroutine.
    suspend fun fetchData() {
        devMode = runCatching { active.readConfig().mockEnabled }.getOrNull() ?: devMode
        val now = nowMs()
        window48 = runCatching {
            active.requestRawReadings(fromMs = now - WINDOW_HOURS * HOUR_MS, toMs = now)
        }.getOrNull()
        weatherUpdatedMs = runCatching { active.readStatus().weatherUpdatedMs }.getOrNull()
        state = try {
            PredState.Loaded(active.requestPredictions())
        } catch (e: Throwable) {
            PredState.Failed(e.message ?: "no se pudieron leer las predicciones")
        }
    }

    LaunchedEffect(Unit) { state = PredState.Loading; fetchData() }

    // Tell the user how it went; on failure, offer to open the device logs.
    suspend fun report(ok: Boolean, message: String) {
        if (ok) {
            snackbarHostState.showSnackbar(message)
        } else {
            val res = snackbarHostState.showSnackbar(
                message = message, actionLabel = "Ver logs", duration = SnackbarDuration.Long,
            )
            if (res == SnackbarResult.ActionPerformed) logsDialog = true
        }
    }

    // Run a station action behind a fade-in loading overlay (content stays on
    // screen instead of blanking), then refresh the data and report the outcome.
    fun runAction(label: String, successMsg: String?, failMsg: String, block: suspend () -> Unit) {
        scope.launch {
            loadingLabel = label
            refreshing = true
            val result = runCatching { block() }
            if (result.isSuccess) runCatching { fetchData() }
            refreshing = false
            if (result.isFailure) report(false, result.exceptionOrNull()?.message ?: failMsg)
            else if (successMsg != null) report(true, successMsg)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Predicciones", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { BackIconButton(onClick = onBack) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (val s = state) {
                PredState.Loading -> Centered { CircularProgressIndicator() }
                is PredState.Failed -> Centered {
                    Text(s.message, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { scope.launch { state = PredState.Loading; fetchData() } }) {
                        Text("Reintentar")
                    }
                }
                is PredState.Loaded -> PredictionsContent(
                    predictions = s.predictions,
                    window48 = window48,
                    weatherUpdatedMs = weatherUpdatedMs,
                    devMode = devMode,
                    onIngest = { point ->
                        runAction("Enviando medida…", "Medida enviada ✓", "No se pudo enviar la medida") {
                            active.ingest(listOf(point))
                        }
                    },
                    onMockPred = {
                        runAction("Simulando…", "Pronóstico simulado ✓", "No se pudo simular el pronóstico") {
                            active.mockReading("pred")
                        }
                    },
                    onReload = {
                        runAction("Actualizando…", null, "No se pudo actualizar") { }
                    },
                )
            }
            LoadingOverlay(visible = refreshing, label = loadingLabel)
        }
    }

    if (logsDialog) LogsDialog(active = active, onDismiss = { logsDialog = false })
}

// A soft loading veil: dims (not blanks) the content and floats a spinner pill,
// fading in/out so a quick action doesn't tear the screen down.
@Composable
private fun LoadingOverlay(visible: Boolean, label: String) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.2f))
                // Swallow taps so controls underneath aren't pressed mid-action.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 6.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                    Spacer(Modifier.width(14.dp))
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

// Minimalist logs viewer: pulls the device log ring and shows it in a tidy
// monospace panel inside the shared modal.
@Composable
private fun LogsDialog(active: ActiveSession, onDismiss: () -> Unit) {
    var logs by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { active.requestLogs() }
            .onSuccess { logs = it }
            .onFailure { error = it.message ?: "No se pudieron leer los logs" }
    }
    TerraDialog(
        onDismiss = onDismiss,
        title = "Logs del dispositivo",
        confirmText = "Cerrar",
        onConfirm = onDismiss,
        dismissText = null,
    ) {
        when {
            error != null -> Text(
                error ?: "", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            logs == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Leyendo logs…", style = MaterialTheme.typography.bodySmall)
            }
            logs.isNullOrEmpty() -> Text(
                "Sin logs todavía", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            ) {
                Column(
                    modifier = Modifier
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    logs?.forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PredictionsContent(
    predictions: List<Prediction>,
    window48: List<Reading>?,
    weatherUpdatedMs: Long?,
    devMode: Boolean,
    onIngest: (IngestPoint) -> Unit,
    onMockPred: () -> Unit,
    onReload: () -> Unit,
) {
    val forecast = predictions.filter { it.kind == KIND_HS30_FORECAST }.sortedBy { it.tsMs }
    val recommendation = predictions.lastOrNull { it.kind == KIND_RECOMMENDATION }?.value?.toInt()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (window48 != null) InferenceProgressCard(window48, weatherUpdatedMs, devMode, onIngest)

        if (predictions.isEmpty()) {
            Text(
                "Aún no hay predicciones; se generan a la hora de predicción automática, si hay datos suficientes (Configuración › Modelo e inferencia).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (forecast.isNotEmpty()) {
            LastPredictionCard(forecast = forecast, recommendation = recommendation)
            ForecastCard(forecast)
        } else {
            RecommendationCard(recommendation)
        }

        // Dev: ask the station to publish a synthetic 24 h LSTM forecast (hidden unless dev mode).
        if (devMode) {
            OutlinedButton(onClick = onMockPred, modifier = Modifier.fillMaxWidth()) {
                Text("Simular pronóstico LSTM (dev)")
            }
        }
        Button(onClick = onReload, modifier = Modifier.fillMaxWidth()) { Text("Actualizar") }
    }
}

// Build one ingest point at the next still-empty hour of a series (count = how
// many hours are already filled), so repeated presses walk backwards hour by hour
// and fill the 48 h window with distinct timestamps the upsert treats as new.
private fun mockPoint(kind: String, depth: Int, value: Double, count: Int): IngestPoint {
    val hour = nowMs() / HOUR_MS * HOUR_MS
    return IngestPoint(tsMs = hour - count.toLong() * HOUR_MS, kind = kind, value = value, depthCm = depth)
}

// How many DISTINCT hourly buckets a series covers inside the window. Counting rows
// instead would let a station sampling every minute report "48/48" after 48 minutes,
// because the station folds each clock hour into one mean before the model sees it.
private fun List<Reading>.hourlyCoverage(kind: String, depth: Int): Int =
    asSequence()
        .filter { it.kind == kind && it.depthCm == depth }
        .map { it.tsMs / HOUR_MS }
        .distinct()
        .count()

/** Hours since the newest soil bucket of any depth; null when there is none. */
private fun List<Reading>.newestSoilAgeHours(): Long? =
    asSequence()
        .filter { it.kind == ReadingKind.SOIL_MOISTURE }
        .maxOfOrNull { it.tsMs / HOUR_MS }
        ?.let { (nowMs() / HOUR_MS) - it }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InferenceProgressCard(
    window48: List<Reading>,
    weatherUpdatedMs: Long?,
    devMode: Boolean,
    onIngest: (IngestPoint) -> Unit,
) {
    val hs10 = window48.hourlyCoverage(ReadingKind.SOIL_MOISTURE, 10)
    val hs30 = window48.hourlyCoverage(ReadingKind.SOIL_MOISTURE, 30)
    // TA (past 48 h AND the next 24 h) is not measured here: it comes from the
    // weather cache the backend pushes over LoRa/BLE, and the station refuses to
    // infer without a full one. All the app can see of it is when it last landed.
    val hasForecast = weatherUpdatedMs != null
    // Coverage counts how MUCH of the window is real, never how recent: a probe that
    // died hours ago still scores high and would otherwise read as "listo".
    val soilAge = window48.newestSoilAgeHours()
    val soilStale = soilAge != null && soilAge > STALE_SOIL_HOURS
    val ready = hs10 >= MIN_HOURS && hs30 >= MIN_HOURS && hasForecast && !soilStale

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Datos para la inferencia", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                text = when {
                    ready -> "Listo para inferir"
                    soilStale -> "La sonda no da lecturas desde hace $soilAge h"
                    !hasForecast -> "Falta el pronóstico de temperatura del aire"
                    else -> "El LSTM necesita al menos $MIN_HOURS h reales de humedad"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            SeriesRow("HS10 · humedad 10 cm", hs10, WINDOW_HOURS)
            Spacer(Modifier.height(12.dp))
            SeriesRow("HS30 · humedad 30 cm", hs30, WINDOW_HOURS)
            Spacer(Modifier.height(12.dp))
            ForecastRow(weatherUpdatedMs)
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Última lectura de suelo", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = when {
                        soilAge == null -> "ninguna"
                        soilAge <= 0L -> "esta hora"
                        soilAge == 1L -> "hace 1 h"
                        else -> "hace $soilAge h"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (soilStale || soilAge == null) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Cada paso es una hora de reloj: las lecturas de una misma hora se " +
                    "promedian en un solo valor. Por debajo de $MIN_HOURS h la estación no " +
                    "infiere, y hasta las $WINDOW_HOURS h rellena los huecos con el último valor.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (devMode) {
                Spacer(Modifier.height(16.dp))
                Text("Enviar una medida (dev · ingest)", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                // No TA button: the model reads air temperature from the weather
                // cache, so ingesting an air_temperature reading would move this
                // card without moving the inference.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onIngest(mockPoint(ReadingKind.SOIL_MOISTURE, 10, 0.70, hs10)) },
                        enabled = hs10 < WINDOW_HOURS, modifier = Modifier.weight(1f),
                    ) { Text("+ HS10") }
                    OutlinedButton(
                        onClick = { onIngest(mockPoint(ReadingKind.SOIL_MOISTURE, 30, 0.74, hs30)) },
                        enabled = hs30 < WINDOW_HOURS, modifier = Modifier.weight(1f),
                    ) { Text("+ HS30") }
                }
            }
        }
    }
}

/** The TA window (48 h past + 24 h forecast). It is delivered whole or not at all,
 *  so it reports availability and age rather than a count. */
@Composable
private fun ForecastRow(updatedMs: Long?) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("TA · pronóstico 48 h + 24 h", style = MaterialTheme.typography.bodyMedium)
        Text(
            text = if (updatedMs != null) formatRelativeMs(updatedMs) else "sin pronóstico",
            style = MaterialTheme.typography.bodySmall,
            color = if (updatedMs != null) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeriesRow(label: String, taken: Int, target: Int) {
    val capped = taken.coerceIn(0, target)
    val missing = (target - taken).coerceAtLeast(0)
    val takenColor = Color(0xFF2E7D32)                          // green = measured
    val pendingColor = MaterialTheme.colorScheme.surfaceVariant // grey = pending
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = if (missing == 0) "$target/$target ✓" else "$capped/$target · faltan $missing",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(6.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(target) { i ->
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (i < capped) takenColor else pendingColor),
            )
        }
    }
}

@Composable
private fun LastPredictionCard(forecast: List<Prediction>, recommendation: Int?) {
    val values = forecast.map { it.value }
    val min = values.min()
    val max = values.max()
    val model = forecast.firstOrNull()?.model ?: "lstm-hs30"
    val confidence = forecast.mapNotNull { it.confidence }.takeIf { it.isNotEmpty() }?.average()

    val (recoLabel, recoDetail) = when (recommendation) {
        1 -> "Regar mañana" to "El modelo prevé estrés hídrico en las próximas 24 h."
        0 -> "No regar" to "La humedad prevista se mantiene en rango saludable."
        else -> "Sin recomendación" to "Aún no hay salida del clasificador."
    }
    val accent = when (recommendation) {
        1 -> MaterialTheme.colorScheme.tertiary    // riego -> agua (teal)
        0 -> MaterialTheme.colorScheme.primary     // sano -> verde
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val specs = buildList {
        add(Spec("Modelo", model))
        add(Spec("Horizonte", "${forecast.size} h"))
        add(Spec("Desde", "${formatDateTime(forecast.first().tsMs)} UTC"))
        add(Spec("Hasta", "${formatDateTime(forecast.last().tsMs)} UTC"))
        add(Spec("HS30 próximo", fmt(forecast.first().value)))
        add(Spec("HS30 mín / máx", "${fmt(min)} / ${fmt(max)}"))
        if (confidence != null) add(Spec("Confianza", "${(confidence * 100).toInt()}%"))
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            SectionHeader("Última predicción", accent = accent)
            Spacer(Modifier.height(12.dp))
            Text(recoLabel, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                recoDetail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            SpecTable(rows = specs)
        }
    }
}

@Composable
private fun RecommendationCard(recommendation: Int?) {
    val (label, detail) = when (recommendation) {
        1 -> "Regar mañana" to "El modelo prevé estrés hídrico en las próximas 24 h."
        0 -> "No regar" to "La humedad prevista se mantiene en rango saludable."
        else -> "Sin recomendación" to "No hay salida del clasificador todavía."
    }
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Recomendación de riego", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(detail, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ForecastCard(forecast: List<Prediction>) {
    val values = forecast.map { it.value }
    val min = values.min()
    val max = values.max()
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Pronóstico HS30 (24 h)", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Humedad volumétrica a 30 cm · mín ${fmt(min)} · máx ${fmt(max)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            forecast.forEachIndexed { i, p ->
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("H+${i + 1}", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${formatDateTime(p.tsMs)} UTC",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(fmt(p.value), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { content() }
}

private const val MS_PER_DAY = 86_400_000L

// Epoch ms (UTC) -> "MM-DD HH:MM" via the civil-from-days algorithm (no platform
// date API; same approach as SyncScreen). Forecast timestamps are UTC.
private fun formatDateTime(ms: Long): String {
    val epochDay = ms / MS_PER_DAY
    val msOfDay = ms % MS_PER_DAY
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

private fun fmt(v: Double): String {
    // Two-decimal VWC without depending on platform String.format.
    val neg = v < 0
    val scaled = ((if (neg) -v else v) * 100).toLong()
    val whole = scaled / 100
    val frac = scaled % 100
    // Track the sign separately: for v in (-1, 0) `whole` is 0 and would drop it.
    val sign = if (neg && (whole != 0L || frac != 0L)) "-" else ""
    val fracStr = if (frac < 10) "0$frac" else "$frac"
    return "$sign$whole.$fracStr"
}
