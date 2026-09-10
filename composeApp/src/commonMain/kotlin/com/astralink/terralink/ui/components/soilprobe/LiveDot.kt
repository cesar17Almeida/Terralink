package com.astralink.terralink.ui.components.soilprobe

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Live indicator: a dot that keeps sending out a ring while readings flow, and
 *  goes still (and red) the moment the probe stops answering. */
@Composable
fun LiveDot(live: Boolean, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val tone by animateColorAsState(if (live) cs.primary else cs.error, tween(300), label = "liveTone")

    val pulse by rememberInfiniteTransition(label = "live").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "pulse",
    )

    // The box leaves room for the widest ring so the pulse is never clipped.
    Box(
        modifier = modifier
            .size(24.dp)
            .semantics { contentDescription = if (live) "Lecturas en vivo" else "Sin señal de la sonda" },
    ) {
        if (live) {
            Canvas(Modifier.matchParentSize()) {
                // Ring grows to 2.6x the dot and fades over the first 70 % of the cycle, then rests.
                val p = (pulse / 0.7f).coerceAtMost(1f)
                drawCircle(tone.copy(alpha = (1f - p) * 0.9f), radius = 4.dp.toPx() * (1f + 1.6f * p))
            }
        }
        Box(Modifier.align(Alignment.Center).size(8.dp).background(tone, CircleShape))
    }
}
