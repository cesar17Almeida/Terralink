package com.astralink.terralink.ui.components.soilprobe

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The "en vivo" badge: a dot that keeps sending out a ring while readings flow, and
 *  goes still (and red) the moment the probe stops answering. */
@Composable
fun LivePill(live: Boolean, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val tone = if (live) cs.primary else cs.error
    val fill = (if (live) cs.primaryContainer else cs.errorContainer).copy(alpha = 0.45f)

    val pulse by rememberInfiniteTransition(label = "live").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "pulse",
    )

    Row(
        modifier = modifier
            .height(28.dp)
            .border(1.dp, tone.copy(alpha = 0.35f), CircleShape)
            .background(fill, CircleShape)
            .padding(start = 9.dp, end = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.size(7.dp)) {
            if (live) {
                Canvas(Modifier.matchParentSize()) {
                    // Ring grows to 2.6x and fades over the first 70 % of the cycle, then rests.
                    val p = (pulse / 0.7f).coerceAtMost(1f)
                    drawCircle(tone.copy(alpha = 1f - p), radius = size.minDimension / 2 * (1f + 1.6f * p))
                }
            }
            Box(Modifier.matchParentSize().background(tone, CircleShape))
        }
        Text(
            text = if (live) "EN VIVO" else "SIN SEÑAL",
            style = microLabelStyle().copy(fontSize = 10.sp, color = tone),
        )
    }
}
