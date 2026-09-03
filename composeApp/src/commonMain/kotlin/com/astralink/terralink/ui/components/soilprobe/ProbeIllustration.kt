package com.astralink.terralink.ui.components.soilprobe

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The probe in the ground, as a diagram: soil strata darkening with depth, the rod
 * with a slow upward flow inside it, and one node per sensor. The selected node
 * grows; every node keeps sending a faint ring out. Drawn on one Canvas so the
 * pieces stay aligned with the depth rows next to it (one stratum per row).
 */
@Composable
fun ProbeIllustration(count: Int, selected: Int?, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val strataTop = cs.surfaceContainerLow
    val strataBottom = cs.surfaceContainerHigh
    val hairline = cs.outlineVariant
    val rodEdge = cs.outlineVariant
    val rodMid = cs.surfaceContainerHigh
    val rodHighlight = cs.surfaceContainerLowest
    val tipEdge = cs.onSurfaceVariant
    val tipMid = cs.outline
    val cap = cs.primary
    val flowColor = cs.primaryContainer
    val nodeRing = cs.primaryContainer
    val nodeBorder = cs.surfaceContainerLowest

    val transition = rememberInfiniteTransition(label = "probe")
    val flow by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "flow",
    )
    val ring by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "ring",
    )
    val dotSizes: List<Dp> = (0 until count).map { i ->
        animateDpAsState(if (selected == i) 16.dp else 11.dp, tween(250), label = "dot$i").value
    }

    Canvas(modifier.clipToBounds()) {
        val w = size.width
        val h = size.height
        val top = 8.dp.toPx()
        val rodW = 16.dp.toPx()
        val rodLeft = w / 2 - rodW / 2
        val rodBottom = h - 26.dp.toPx()
        val one = 1.dp.toPx()

        // Strata: one band per sensor, darker as it goes down.
        val bandH = h / count
        for (i in 0 until count) {
            val t = if (count > 1) i.toFloat() / (count - 1) else 1f
            drawRect(lerp(strataTop, strataBottom, t), Offset(0f, i * bandH), Size(w, bandH + 0.5f))
        }
        drawRect(hairline, Offset(0f, top), Size(w, one))   // the surface line

        // Rod: rounded only at the top, cylinder shading left to right.
        val rod = Rect(rodLeft, top, rodLeft + rodW, rodBottom)
        val rodPath = Path().apply {
            addRoundRect(
                RoundRect(
                    rod, topLeft = CornerRadius(8.dp.toPx()), topRight = CornerRadius(8.dp.toPx()),
                    bottomRight = CornerRadius.Zero, bottomLeft = CornerRadius.Zero,
                ),
            )
        }
        drawPath(
            rodPath,
            Brush.horizontalGradient(
                0f to rodEdge, 0.18f to rodMid, 0.45f to rodHighlight, 0.74f to rodMid, 1f to rodEdge,
                startX = rodLeft, endX = rodLeft + rodW,
            ),
        )

        // Tip: a cone overlapping the rod's end by 2 dp.
        val tipTop = rodBottom - 2.dp.toPx()
        val tip = Path().apply {
            moveTo(rodLeft, tipTop)
            lineTo(rodLeft + rodW, tipTop)
            lineTo(w / 2, h - 8.dp.toPx())
            close()
        }
        drawPath(
            tip,
            Brush.horizontalGradient(
                0f to tipEdge, 0.55f to tipMid, 1f to tipEdge, startX = rodLeft, endX = rodLeft + rodW,
            ),
        )

        // Cap, half above the surface (the column clips it).
        drawRoundRect(
            cap, Offset(w / 2 - 17.dp.toPx(), -6.dp.toPx()), Size(34.dp.toPx(), 20.dp.toPx()),
            CornerRadius(6.dp.toPx()),
        )

        // Flow: thin stripes climbing the rod.
        clipRect(rod.left, rod.top, rod.right, rod.bottom) {
            val period = 10.dp.toPx()
            val shift = flow * 18.dp.toPx()
            var y = rod.top - period * 2 - shift
            while (y < rod.bottom) {
                drawRect(flowColor.copy(alpha = 0.6f), Offset(rod.left, y + 8.dp.toPx()), Size(rodW, 2.dp.toPx()))
                y += period
            }
        }

        // Nodes: hairline to the row, a fading halo, then the dot with a light border.
        for (i in 0 until count) {
            val center = Offset(w / 2, h * (i + 0.5f) / count)
            val dotR = dotSizes[i].toPx() / 2
            drawRect(hairline.copy(alpha = 0.6f), Offset(w / 2, center.y), Size(w / 2, one))
            drawCircle(nodeRing.copy(alpha = (1f - ring) * 0.9f), dotR + 12.dp.toPx() * ring, center)
            drawCircle(nodeBorder, dotR, center)
            drawCircle(cap, dotR - 2.dp.toPx(), center)
        }
    }
}
