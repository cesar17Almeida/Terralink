// The two drawings of the cadence step: the clock that marks it, and the rhythm
// strip that turns "cada 5 min" into something you can see -- one tick per reading
// over a fixed window, so picking a shorter interval visibly crowds the line.
package com.astralink.terralink.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Ticks the strip will draw; beyond this the window is redrawn coarser, not denser. */
private const val MAX_TICKS = 60

/** Seconds the rhythm strip spans: an hour, or a day once the interval outgrows it. */
fun cadenceWindowS(intervalS: Int): Int = if (intervalS <= 3600) 3600 else 86_400

/** The clock face that heads the cadence step: a sweeping hand, so the step reads
 *  as "how often" before a word is read. */
@Composable
fun CadenceClock(modifier: Modifier = Modifier) {
    val sweep by rememberInfiniteTransition(label = "clock").animateFloat(
        initialValue = 0f, targetValue = 1f, label = "hand",
        animationSpec = infiniteRepeatable(tween(3600, easing = LinearEasing)),
    )
    val face = MaterialTheme.colorScheme.primaryContainer
    val ink = MaterialTheme.colorScheme.onPrimaryContainer
    Canvas(Modifier.size(56.dp).then(modifier)) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension / 2f
        drawCircle(face, r, c)
        drawCircle(ink.copy(alpha = 0.30f), r - 1.dp.toPx(), c, style = Stroke(1.5.dp.toPx()))
        for (i in 0 until 12) {                       // hour marks
            val a = i * PI.toFloat() / 6f
            val outer = Offset(c.x + sin(a) * (r - 5.dp.toPx()), c.y - cos(a) * (r - 5.dp.toPx()))
            drawCircle(ink.copy(alpha = if (i % 3 == 0) 0.55f else 0.25f), 1.dp.toPx(), outer)
        }
        fun hand(turns: Float, length: Float, width: Float, alpha: Float) {
            val a = turns * 2f * PI.toFloat()
            drawLine(
                ink.copy(alpha = alpha), c,
                Offset(c.x + sin(a) * length, c.y - cos(a) * length),
                strokeWidth = width, cap = StrokeCap.Round,
            )
        }
        hand(sweep / 12f, r * 0.42f, 2.5.dp.toPx(), 0.75f)   // hour
        hand(sweep, r * 0.62f, 2.dp.toPx(), 1f)              // minute
        drawCircle(ink, 2.dp.toPx(), c)
    }
}

/**
 * One tick per reading across [cadenceWindowS], with a playhead sweeping it: the
 * same window at every interval, so 1 min reads as a dense comb and 1 h as a couple
 * of marks. Ticks light as the playhead passes them.
 */
@Composable
fun CadenceRhythm(intervalS: Int, modifier: Modifier = Modifier) {
    val sweep by rememberInfiniteTransition(label = "rhythm").animateFloat(
        initialValue = 0f, targetValue = 1f, label = "sweep",
        animationSpec = infiniteRepeatable(tween(4000, easing = LinearEasing)),
    )
    val accent = MaterialTheme.colorScheme.primary
    val base = MaterialTheme.colorScheme.outlineVariant
    val ticks = (cadenceWindowS(intervalS) / intervalS).coerceIn(1, MAX_TICKS)

    Canvas(Modifier.fillMaxWidth().height(44.dp).then(modifier)) {
        val left = 2.dp.toPx()
        val right = size.width - 2.dp.toPx()
        val baseY = size.height - 8.dp.toPx()
        drawLine(base, Offset(left, baseY), Offset(right, baseY), strokeWidth = 1.5.dp.toPx())
        val headX = left + (right - left) * sweep
        for (i in 0..ticks) {
            val x = left + (right - left) * (i.toFloat() / ticks)
            // Near the playhead a tick grows and takes the accent: that is a reading.
            val near = (1f - abs(x - headX) / 34.dp.toPx()).coerceIn(0f, 1f)
            val h = 9.dp.toPx() + 13.dp.toPx() * near
            drawLine(
                lerp(base, accent, near),
                Offset(x, baseY), Offset(x, baseY - h),
                strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round,
            )
            if (near > 0.85f) drawCircle(accent.copy(alpha = near), 2.5.dp.toPx(), Offset(x, baseY - h - 4.dp.toPx()))
        }
    }
}
