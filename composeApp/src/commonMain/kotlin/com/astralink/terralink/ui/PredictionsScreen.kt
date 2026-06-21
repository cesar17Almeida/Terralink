package com.astralink.terralink.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.astralink.terralink.ble.protocol.IngestPoint
import com.astralink.terralink.ble.protocol.Prediction
import com.astralink.terralink.ble.protocol.Reading
import com.astralink.terralink.ble.protocol.ReadingKind
import com.astralink.terralink.ble.session.ActiveSession
import com.astralink.terralink.model.SavedStation
import com.astralink.terralink.ui.components.BackIconButton
import com.astralink.terralink.util.nowMs
import kotlinx.coroutines.launch

private const val KIND_HS30_FORECAST = "hs30_forecast"
private const val KIND_RECOMMENDATION = "water_recommendation"

// The LSTM's past window: 48 h per series (HS10, HS30, TA).
private const val WINDOW_HOURS = 48

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
    var reloadKey by remember { mutableStateOf(0) }

    // The firmware handles ONE data_request at a time and the notify stream is
    // shared, so these MUST run sequentially in a single coroutine -- two
    // concurrent reads corrupt each other (readings would silently fail -> no dots).
    val scope = rememberCoroutineScope()
    var window48 by remember { mutableStateOf<List<Reading>?>(null) }
    LaunchedEffect(reloadKey) {
        state = PredState.Loading
        val now = nowMs()
        window48 = runCatching {
            active.requestRawReadings(fromMs = now - 48L * 3_600_000L, toMs = now)
        }.getOrNull()
        state = try {
            PredState.Loaded(active.requestPredictions())
        } catch (e: Throwable) {
            PredState.Failed(e.message ?: "no se pudieron leer las predicciones")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Predicciones", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { BackIconButton(onClick = onBack) },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (val s = state) {
                PredState.Loading -> Centered { CircularProgressIndicator() }
                is PredState.Failed -> Centered {
                    Text(s.message, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { reloadKey++ }) { Text("Reintentar") }
                }
                is PredState.Loaded -> PredictionsContent(
                    predictions = s.predictions,
                    window48 = window48,
                    onIngest = { point -> scope.launch { runCatching { active.ingest(listOf(point)) }; reloadKey++ } },
                    onMockPred = { scope.launch { runCatching { active.mockReading("pred") }; reloadKey++ } },
                    onReload = { reloadKey++ },
                )
            }
        }
    }
}

@Composable
private fun PredictionsContent(
    predictions: List<Prediction>,
    window48: List<Reading>?,
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
        if (window48 != null) InferenceProgressCard(window48, onIngest)

        if (predictions.isEmpty()) {
            Text(
                "Aún no hay predicciones; se generan en el ciclo diario cuando hay datos suficientes.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            RecommendationCard(recommendation)
            if (forecast.isNotEmpty()) ForecastCard(forecast)
        }

        // Dev: ask the station to publish a synthetic 24 h LSTM forecast.
        OutlinedButton(onClick = onMockPred, modifier = Modifier.fillMaxWidth()) {
            Text("Simular pronóstico LSTM (dev)")
        }
        Button(onClick = onReload, modifier = Modifier.fillMaxWidth()) { Text("Actualizar") }
    }
}

// Build one ingest point at the next still-empty hour of a series (count = how
// many hours are already filled), so repeated presses walk backwards hour by hour
// and fill the 48 h window with distinct timestamps the upsert treats as new.
private fun mockPoint(kind: String, depth: Int, value: Double, count: Int): IngestPoint {
    val hour = nowMs() / 3_600_000L * 3_600_000L
    return IngestPoint(tsMs = hour - count.toLong() * 3_600_000L, kind = kind, value = value, depthCm = depth)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InferenceProgressCard(window48: List<Reading>, onIngest: (IngestPoint) -> Unit) {
    val hs10 = window48.count { it.kind == "soil_moisture" && it.depthCm == 10 }
    val hs30 = window48.count { it.kind == "soil_moisture" && it.depthCm == 30 }
    val ta = window48.count { it.kind == "air_temperature" }
    val ready = hs10 >= WINDOW_HOURS && hs30 >= WINDOW_HOURS && ta >= WINDOW_HOURS

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
                text = if (ready) "Listo: las 3 series tienen sus 48 h"
                       else "El LSTM necesita 48 h por serie (HS10, HS30 y TA)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            SeriesRow("HS10 · humedad 10 cm", hs10, WINDOW_HOURS)
            Spacer(Modifier.height(12.dp))
            SeriesRow("HS30 · humedad 30 cm", hs30, WINDOW_HOURS)
            Spacer(Modifier.height(12.dp))
            SeriesRow("TA · temperatura del aire", ta, WINDOW_HOURS)

            Spacer(Modifier.height(16.dp))
            Text("Enviar una medida (dev · ingest)", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onIngest(mockPoint(ReadingKind.SOIL_MOISTURE, 10, 0.70, hs10)) },
                    enabled = hs10 < WINDOW_HOURS, modifier = Modifier.weight(1f),
                ) { Text("+ HS10") }
                OutlinedButton(
                    onClick = { onIngest(mockPoint(ReadingKind.SOIL_MOISTURE, 30, 0.74, hs30)) },
                    enabled = hs30 < WINDOW_HOURS, modifier = Modifier.weight(1f),
                ) { Text("+ HS30") }
                OutlinedButton(
                    onClick = { onIngest(mockPoint(ReadingKind.AIR_TEMPERATURE, 0, 22.0, ta)) },
                    enabled = ta < WINDOW_HOURS, modifier = Modifier.weight(1f),
                ) { Text("+ TA") }
            }
        }
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
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("H+${i + 1}", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
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

private fun fmt(v: Double): String {
    // Two-decimal VWC without depending on platform String.format.
    val scaled = (v * 100).toLong()
    val whole = scaled / 100
    val frac = (scaled % 100).let { if (it < 0) -it else it }
    val fracStr = if (frac < 10) "0$frac" else "$frac"
    return "$whole.$fracStr"
}
