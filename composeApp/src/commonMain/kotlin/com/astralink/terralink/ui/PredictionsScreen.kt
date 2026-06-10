package com.astralink.terralink.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import com.astralink.terralink.ble.protocol.Prediction
import com.astralink.terralink.ble.session.ActiveSession
import com.astralink.terralink.model.SavedStation

private const val KIND_HS30_FORECAST = "hs30_forecast"
private const val KIND_RECOMMENDATION = "water_recommendation"

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

    LaunchedEffect(reloadKey) {
        state = PredState.Loading
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
                navigationIcon = { TextButton(onClick = onBack) { Text("Atrás") } },
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
                is PredState.Loaded -> PredictionsContent(s.predictions, onReload = { reloadKey++ })
            }
        }
    }
}

@Composable
private fun PredictionsContent(predictions: List<Prediction>, onReload: () -> Unit) {
    val forecast = predictions.filter { it.kind == KIND_HS30_FORECAST }.sortedBy { it.tsMs }
    val recommendation = predictions.lastOrNull { it.kind == KIND_RECOMMENDATION }?.value?.toInt()

    if (predictions.isEmpty()) {
        Centered {
            Text(
                "Aún no hay predicciones. Pulsa \"Forzar inferencia\" en la estación.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onReload) { Text("Actualizar") }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RecommendationCard(recommendation)
        if (forecast.isNotEmpty()) ForecastCard(forecast)
        Button(onClick = onReload, modifier = Modifier.fillMaxWidth()) { Text("Actualizar") }
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
