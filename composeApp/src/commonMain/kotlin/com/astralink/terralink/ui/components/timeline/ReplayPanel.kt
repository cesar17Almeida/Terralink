// The developer panel that drives the dataset replay, and the report it leaves
// behind. Hidden unless the station has its `mock` flag on -- the replay only
// behaves as intended on a station that is not sampling a real probe.
package com.astralink.terralink.ui.components.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astralink.terralink.timeline.ReplayReport
import com.astralink.terralink.timeline.ReplayStage
import com.astralink.terralink.timeline.ReplayWindow
import com.astralink.terralink.timeline.fmtDecimals

@Composable
fun ReplayPanel(
    window: ReplayWindow?,
    stage: ReplayStage?,
    report: ReplayReport?,
    onRun: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = timeTones()
    val busy = stage is ReplayStage.Step
    Column(modifier.fillMaxWidth()) {
        HorizontalDivider(color = t.hairline)
        Spacer(Modifier.height(16.dp))
        Text("ENSAYO CON DATOS REALES", style = eyebrow(t.faint))
        Spacer(Modifier.height(8.dp))
        Text(
            if (window == null) "No se pudo leer la ventana del dataset."
            else "Ventana medida del nodo ${window.node} (${window.startedAt.take(10)}): " +
                "48 h de HS10/HS20/HS30 + la temperatura del aire que el modelo necesita, " +
                "colocadas para terminar en la hora actual. La estación ejecuta el LSTM sobre " +
                "ellas y luego se inyecta el HS30 realmente medido de las 24 h siguientes.",
            style = TextStyle(fontSize = 12.5.sp, lineHeight = 20.sp, color = t.muted),
        )
        Spacer(Modifier.height(14.dp))

        val shape = RoundedCornerShape(percent = 50)
        Box(
            Modifier
                .height(38.dp)
                .background(if (busy || window == null) Color.Transparent else t.accent, shape)
                .border(1.dp, if (busy || window == null) t.hairline else t.accent, shape)
                .clickable(enabled = !busy && window != null, onClick = onRun)
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.5.dp, color = t.muted)
                    Spacer(Modifier.width(9.dp))
                }
                Text(
                    if (busy) "EJECUTANDO…" else "CARGAR VENTANA Y EJECUTAR EL LSTM",
                    style = eyebrow(
                        if (busy || window == null) t.muted else MaterialTheme.colorScheme.onPrimary, 8.5f,
                    ),
                )
            }
        }

        when (stage) {
            is ReplayStage.Step -> {
                Spacer(Modifier.height(10.dp))
                Text("${stage.index}/${stage.total} · ${stage.label}",
                    style = TextStyle(fontSize = 12.sp, color = t.muted))
            }
            is ReplayStage.Failed -> {
                Spacer(Modifier.height(10.dp))
                Text("Falló en «${stage.label}»: ${stage.message}",
                    style = TextStyle(fontSize = 12.sp, lineHeight = 19.sp, color = t.alert))
            }
            else -> Unit
        }

        if (report != null) {
            Spacer(Modifier.height(16.dp))
            ReplayReportCard(report)
        }
    }
}

/**
 * The three numbers that matter after a run, and they answer different questions:
 * whether the board ran the model at all, whether its answer matches the same
 * model on a workstation (any gap is TFLM / int8 / the arena, not the data), and
 * how far both are from the hours that were actually measured.
 */
@Composable
private fun ReplayReportCard(r: ReplayReport) {
    val t = timeTones()
    Column {
        Text("RESULTADO", style = eyebrow(t.faint))
        Spacer(Modifier.height(10.dp))
        if (!r.inferenceQueued) {
            Text(
                "La estación rechazó la petición: o el firmware no lleva inferencia " +
                    "on-device, o está en modo reenvío. Los datos sí quedaron inyectados.",
                style = TextStyle(fontSize = 12.5.sp, lineHeight = 20.sp, color = t.alert),
            )
            return@Column
        }
        if (r.stationForecast.isEmpty()) {
            Text(
                "Se encoló la inferencia pero no llegó ningún pronóstico nuevo. " +
                    "Mira los logs de la estación: lo normal es que el modelo haya " +
                    "rechazado la ventana y diga por qué.",
                style = TextStyle(fontSize = 12.5.sp, lineHeight = 20.sp, color = t.alert),
            )
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Metric("MAE placa", r.stationMae, t.ink, Modifier.weight(1f))
            Divider(t.hairline)
            Metric("MAE host", r.hostMae, t.muted, Modifier.weight(1f).padding(start = 14.dp))
            Divider(t.hairline)
            Metric("placa vs host", r.stationVsHost, t.accent, Modifier.weight(1f).padding(start = 14.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "${r.ingestedPast} puntos de historia y ${r.ingestedTruth} de verdad medida " +
                "aceptados por la estación. El pronóstico ya está archivado: aparece arriba " +
                "en cuanto refresques.",
            style = TextStyle(fontSize = 12.sp, lineHeight = 19.sp, color = t.faint),
        )
    }
}

@Composable
private fun Divider(color: Color) {
    Box(Modifier.width(1.dp).height(26.dp).background(color))
}

@Composable
private fun Metric(label: String, value: Double?, color: Color, modifier: Modifier = Modifier) {
    val t = timeTones()
    Column(modifier) {
        Text(value?.let { fmtDecimals(it, 4) } ?: "—", style = monoValue(color, 15f))
        Spacer(Modifier.height(5.dp))
        Text(label.uppercase(), style = eyebrow(t.faint, 8f))
    }
}
