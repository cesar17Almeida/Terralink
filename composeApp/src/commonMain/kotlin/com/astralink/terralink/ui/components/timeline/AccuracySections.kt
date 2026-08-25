// The reading matter around the accuracy chart: the headline numbers, the horizon
// filter, the point readout, and the table view that makes the same data legible
// without relying on the plot at all.
package com.astralink.terralink.ui.components.timeline

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.astralink.terralink.timeline.AccuracyStats
import com.astralink.terralink.timeline.Paired
import com.astralink.terralink.timeline.fmtDecimals

/** The horizons the screen can score separately. Accuracy decays with horizon, and
 *  a single number over all of them hides that. */
enum class HorizonBand(val label: String, val range: IntRange) {
    ALL("Todo", 1..24),
    NEAR("H+1–6", 1..6),
    MID("H+7–12", 7..12),
    FAR("H+13–24", 13..24),
}

@Composable
fun HorizonFilter(
    selected: HorizonBand,
    onSelect: (HorizonBand) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = timeTones()
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        HorizonBand.entries.forEach { band ->
            val on = band == selected
            val shape = RoundedCornerShape(percent = 50)
            Box(
                Modifier
                    .height(28.dp)
                    .background(if (on) t.accent else Color.Transparent, shape)
                    .border(1.dp, if (on) t.accent else t.hairline, shape)
                    .clickable { onSelect(band) }
                    .padding(horizontal = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    band.label.uppercase(),
                    style = TextStyle(
                        fontFamily = Mono, fontSize = 8.5.sp, letterSpacing = 0.1.em,
                        color = if (on) MaterialTheme.colorScheme.onPrimary else t.muted,
                    ),
                )
            }
        }
    }
}

/**
 * The headline numbers. MAE is the one to lead with -- it is in the model's own
 * units, so "0.021 VWC" is a quantity an agronomist can hold, where RMSE and bias
 * answer follow-up questions (how bad are the bad hours, and does it lean).
 */
@Composable
fun AccuracyStatsRow(stats: AccuracyStats?, unit: String, modifier: Modifier = Modifier) {
    val t = timeTones()
    Column(modifier.fillMaxWidth()) {
        HorizontalDivider(color = t.hairline)
        Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Stat(stats?.let { fmtDecimals(it.mae, 3) } ?: "—", "MAE $unit", t.ink, Modifier.weight(1f))
            Divider(t.hairline)
            Stat(stats?.let { fmtDecimals(it.rmse, 3) } ?: "—", "RMSE", t.ink,
                Modifier.weight(1f).padding(start = 14.dp))
            Divider(t.hairline)
            Stat(
                stats?.let { (if (it.bias >= 0) "+" else "") + fmtDecimals(it.bias, 3) } ?: "—",
                "Sesgo",
                stats?.let { if (it.bias >= 0) OverPredictColor else UnderPredictColor } ?: t.ink,
                Modifier.weight(1f).padding(start = 14.dp),
            )
            Divider(t.hairline)
            Stat(stats?.n?.toString() ?: "0", "Horas", t.ink, Modifier.weight(0.7f).padding(start = 14.dp))
        }
        HorizontalDivider(color = t.hairline)
    }
}

@Composable
private fun Divider(color: Color) {
    Box(Modifier.width(1.dp).height(26.dp).background(color))
}

@Composable
private fun Stat(value: String, label: String, color: Color, modifier: Modifier = Modifier) {
    val t = timeTones()
    Column(modifier) {
        Text(value, style = monoValue(color, 15f))
        Spacer(Modifier.height(5.dp))
        Text(label.uppercase(), style = eyebrow(t.faint, 8f))
    }
}

/** The legend. Present whenever two series are, per the rule that identity must
 *  never rest on colour alone -- here it rests on colour, dash and this. */
@Composable
fun AccuracyLegend(modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Key(MeasuredColor, dashed = false, label = "Medido por la sonda")
        Key(PredictedColor, dashed = true, label = "Predicho por el LSTM")
    }
}

/** The swatch is a segment of the line it stands for, dash and all -- a dot would
 *  make the legend a colour key, and colour is only half of the encoding. */
@Composable
private fun Key(color: Color, dashed: Boolean, label: String) {
    val t = timeTones()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(width = 18.dp, height = 9.dp)) {
            drawLine(
                color = color,
                start = Offset(0f, size.height / 2f),
                end = Offset(size.width, size.height / 2f),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
                pathEffect = if (dashed)
                    PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null,
            )
        }
        Spacer(Modifier.width(7.dp))
        Text(label, style = TextStyle(fontSize = 11.5.sp, color = t.muted))
    }
}

/** What the finger is on: the hour, both values, and the error between them. */
@Composable
fun PointReadout(pair: Paired?, utcOffsetMin: Int, unit: String, modifier: Modifier = Modifier) {
    val t = timeTones()
    if (pair == null) {
        Text(
            "ARRASTRA SOBRE EL GRÁFICO PARA LEER UNA HORA",
            modifier = modifier,
            style = eyebrow(t.ghost, 8.5f),
        )
        return
    }
    val err = pair.error
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            hhmm(pair.targetMs + utcOffsetMin * 60_000L),
            style = monoValue(t.ink, 17f),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "real ${fmtDecimals(pair.actual, 3)} · LSTM ${fmtDecimals(pair.predicted, 3)} $unit",
                style = TextStyle(fontSize = 12.sp, color = t.muted),
            )
            Spacer(Modifier.height(3.dp))
            Text(
                (if (err >= 0) "+" else "") + fmtDecimals(err, 3) +
                    " · predicho a H+${pair.horizonH}",
                style = TextStyle(
                    fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    color = if (err >= 0) OverPredictColor else UnderPredictColor,
                ),
            )
        }
    }
}

/** The same data as rows. A plot is a claim; the table is the evidence, and on a
 *  thesis screen someone will want to check it. */
@Composable
fun PairTable(pairs: List<Paired>, utcOffsetMin: Int, modifier: Modifier = Modifier) {
    val t = timeTones()
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            TableCell("Hora", t.faint, 1f, header = true)
            TableCell("Real", t.faint, 1f, header = true)
            TableCell("LSTM", t.faint, 1f, header = true)
            TableCell("Error", t.faint, 1f, header = true)
            TableCell("H+", t.faint, 0.5f, header = true)
        }
        HorizontalDivider(color = t.hairline)
        pairs.forEach { p ->
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                TableCell(hhmm(p.targetMs + utcOffsetMin * 60_000L), t.ink, 1f)
                TableCell(fmtDecimals(p.actual, 3), t.ink, 1f)
                TableCell(fmtDecimals(p.predicted, 3), t.muted, 1f)
                TableCell(
                    (if (p.error >= 0) "+" else "") + fmtDecimals(p.error, 3),
                    if (p.error >= 0) OverPredictColor else UnderPredictColor, 1f,
                )
                TableCell(p.horizonH.toString(), t.faint, 0.5f)
            }
            HorizontalDivider(color = t.hairline.copy(alpha = 0.4f))
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TableCell(
    text: String, color: Color, weight: Float, header: Boolean = false,
) {
    Text(
        text = if (header) text.uppercase() else text,
        modifier = Modifier.weight(weight),
        style = TextStyle(
            fontFamily = Mono,
            fontSize = if (header) 8.5.sp else 11.sp,
            letterSpacing = if (header) 0.12.em else 0.02.em,
            color = color,
        ),
    )
}
