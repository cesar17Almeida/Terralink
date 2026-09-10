// The SDI-12 handshake, drawn: the station sends `?!` down the wire and the probe
// answers with its address. Same visual language as ProbeIllustration (soil strata,
// rod, nodes), so the wizard's detection step and the live probe screen read as the
// same instrument. Purely decorative -- the field's state machine drives [scene].
package com.astralink.terralink.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Which moment of the handshake the illustration is showing. */
enum class ProbeScene { ASKING, FOUND, FAILED }

/** Sensing nodes drawn on the rod -- the AquaCheck's four depths. */
private const val NODES = 4

/** Straight segments the cable is stroked with (a quadratic sampled by hand, so
 *  the pulse can ride the same curve without a PathMeasure). */
private const val CABLE_SEGMENTS = 26

/** One full ask-and-answer cycle. */
private const val CYCLE_MS = 2200

@Composable
fun Sdi12ProbeAnimation(scene: ProbeScene, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val soilTop = cs.surfaceContainerLow
    val soilBottom = cs.surfaceContainerHigh
    val hairline = cs.outlineVariant
    val boardFill = cs.surfaceContainerHighest
    val boardEdge = cs.outline.copy(alpha = 0.6f)
    val boardInk = cs.onSurfaceVariant.copy(alpha = 0.55f)
    val rodBody = cs.surfaceContainerHighest
    val rodEdge = cs.outline.copy(alpha = 0.5f)
    val accent = cs.primary
    val reply = cs.tertiary
    val muted = cs.outline
    val nodeBorder = cs.surfaceContainerLowest
    val error = cs.error

    val cycle by rememberInfiniteTransition(label = "sdi12").animateFloat(
        initialValue = 0f, targetValue = 1f, label = "cycle",
        animationSpec = infiniteRepeatable(tween(CYCLE_MS, easing = LinearEasing)),
    )
    // Cross-fades between scenes, so Asking -> Found settles instead of cutting.
    val lit by animateFloatAsState(if (scene == ProbeScene.FOUND) 1f else 0f, tween(600), label = "lit")
    val dead by animateFloatAsState(if (scene == ProbeScene.FAILED) 1f else 0f, tween(400), label = "dead")
    val asking = scene == ProbeScene.ASKING

    Canvas(Modifier.fillMaxWidth().height(150.dp).then(modifier).clipToBounds()) {
        val w = size.width
        val h = size.height
        val surfaceY = h * 0.44f
        // Half-lit while it asks, fully lit once it answered: finding the address
        // has to look like something happened.
        val live = lerp(muted, accent, maxOf(if (asking) 0.5f else 0f, lit))
        val ink = lerp(live, muted, dead)

        drawSoil(surfaceY, soilTop, soilBottom, hairline)

        // -- station: a small board on legs, left of the probe ------------------
        val boardW = 48.dp.toPx()
        val boardH = 32.dp.toPx()
        val boardLeft = 12.dp.toPx()
        val boardTop = surfaceY - boardH - 18.dp.toPx()
        drawStation(boardLeft, boardTop, boardW, boardH, surfaceY, boardFill, boardEdge, boardInk)
        // The LED: blinking while it asks, steady once answered, nearly out on failure.
        val blink = if (asking) 0.35f + 0.65f * abs(0.5f - cycle) * 2f else 1f - 0.85f * dead
        drawCircle(
            ink.copy(alpha = blink.coerceIn(0.1f, 1f)),
            radius = 2.6.dp.toPx(),
            center = Offset(boardLeft + boardW - 9.dp.toPx(), boardTop + 8.dp.toPx()),
        )

        // -- probe --------------------------------------------------------------
        val rodW = 15.dp.toPx()
        val rodCx = w * 0.72f
        val rodTop = surfaceY - 16.dp.toPx()
        val rodBottom = h - 18.dp.toPx()
        drawProbe(rodCx, rodW, rodTop, rodBottom, h, rodBody, rodEdge, ink)

        // -- cable: board -> probe cap, sagging toward the ground ---------------
        val p0 = Offset(boardLeft + boardW, boardTop + boardH * 0.55f)
        val p2 = Offset(rodCx - 11.dp.toPx(), rodTop - 5.dp.toPx())
        val p1 = Offset((p0.x + p2.x) / 2f, surfaceY - 4.dp.toPx())
        fun cableAt(t: Float): Offset {
            val u = 1f - t
            return Offset(
                u * u * p0.x + 2f * u * t * p1.x + t * t * p2.x,
                u * u * p0.y + 2f * u * t * p1.y + t * t * p2.y,
            )
        }
        val cableColor = lerp(muted.copy(alpha = 0.7f), accent.copy(alpha = 0.55f), lit)
        for (i in 0 until CABLE_SEGMENTS) {
            val mid = (i + 0.5f) / CABLE_SEGMENTS
            // A failed handshake breaks the wire open in the middle: the likeliest cause.
            if (dead > 0.5f && mid > 0.44f && mid < 0.56f) continue
            drawLine(
                cableColor,
                cableAt(i / CABLE_SEGMENTS.toFloat()),
                cableAt((i + 1) / CABLE_SEGMENTS.toFloat()),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }

        // -- the exchange -------------------------------------------------------
        val capCenter = Offset(rodCx, rodTop - 1.dp.toPx())
        if (asking) {
            // `?!` travelling out, the probe's answer travelling back.
            if (cycle < 0.46f) drawPulse(cableAt((cycle / 0.45f).coerceAtMost(1f)), accent)
            if (cycle > 0.52f) drawPulse(cableAt(1f - ((cycle - 0.52f) / 0.45f).coerceAtMost(1f)), reply)
            val ring = ((cycle - 0.42f) / 0.34f).coerceIn(0f, 1f)
            if (ring > 0f && ring < 1f) {
                drawCircle(
                    reply.copy(alpha = 0.45f * (1f - ring)),
                    radius = 9.dp.toPx() + 26.dp.toPx() * ring,
                    center = capCenter,
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        } else if (lit > 0.05f) {
            // Settled: one slow halo, so the found state still breathes.
            val ring = cycle
            drawCircle(
                accent.copy(alpha = 0.28f * (1f - ring) * lit),
                radius = 10.dp.toPx() + 20.dp.toPx() * ring,
                center = capCenter,
                style = Stroke(width = 2.dp.toPx()),
            )
        }
        if (dead > 0.5f) drawBreak(cableAt(0.5f), error)

        // -- sensing nodes ------------------------------------------------------
        val bandH = (h - surfaceY) / NODES
        for (i in 0 until NODES) {
            val center = Offset(rodCx, surfaceY + bandH * (i + 0.42f))
            val r = 5.5.dp.toPx()
            if (asking || lit > 0.05f) {
                // Nodes light in sequence from the top, as if the query ran down the rod.
                val phase = (cycle - 0.45f - i * 0.05f).let { if (it < 0f) it + 1f else it }
                val halo = if (asking) (1f - phase).coerceIn(0f, 1f) else lit * (1f - cycle)
                drawCircle(live.copy(alpha = 0.30f * halo), r + 9.dp.toPx() * (1f - halo), center)
            }
            drawCircle(nodeBorder, r, center)
            drawCircle(ink, r - 2.dp.toPx(), center)
        }
    }
}

/** Strata darkening with depth, plus the hairline that reads as the ground line. */
private fun DrawScope.drawSoil(surfaceY: Float, top: Color, bottom: Color, hairline: Color) {
    val bands = NODES
    val bandH = (size.height - surfaceY) / bands
    for (i in 0 until bands) {
        val t = i / (bands - 1f)
        drawRect(lerp(top, bottom, t), Offset(0f, surfaceY + i * bandH), Size(size.width, bandH + 0.5f))
    }
    drawRect(hairline.copy(alpha = 0.7f), Offset(0f, surfaceY), Size(size.width, 1.dp.toPx()))
}

/** The station: board, two legs into the ground, a chip and a pin strip. */
private fun DrawScope.drawStation(
    left: Float, top: Float, w: Float, h: Float, surfaceY: Float,
    fill: Color, edge: Color, ink: Color,
) {
    for (x in listOf(left + 10.dp.toPx(), left + w - 10.dp.toPx())) {
        drawLine(edge, Offset(x, top + h), Offset(x, surfaceY), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
    }
    val radius = CornerRadius(7.dp.toPx())
    drawRoundRect(fill, Offset(left, top), Size(w, h), radius)
    drawRoundRect(edge, Offset(left, top), Size(w, h), radius, style = Stroke(1.dp.toPx()))
    drawRoundRect(                                  // the microcontroller
        ink.copy(alpha = 0.35f),
        Offset(left + 9.dp.toPx(), top + h - 15.dp.toPx()),
        Size(18.dp.toPx(), 9.dp.toPx()),
        CornerRadius(2.dp.toPx()),
    )
    for (i in 0..2) {                               // header pins along the right edge
        val y = top + 14.dp.toPx() + i * 5.dp.toPx()
        drawLine(
            ink.copy(alpha = 0.5f),
            Offset(left + w - 12.dp.toPx(), y), Offset(left + w - 5.dp.toPx(), y),
            strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round,
        )
    }
}

/** Rod with a rounded head, a cap above the surface and a conical tip. */
private fun DrawScope.drawProbe(
    cx: Float, rodW: Float, top: Float, bottom: Float, h: Float,
    body: Color, edge: Color, cap: Color,
) {
    val left = cx - rodW / 2f
    drawRoundRect(body, Offset(left, top), Size(rodW, bottom - top), CornerRadius(6.dp.toPx()))
    drawRoundRect(edge, Offset(left, top), Size(rodW, bottom - top), CornerRadius(6.dp.toPx()),
        style = Stroke(1.dp.toPx()))
    val tipTop = bottom - 3.dp.toPx()               // overlaps, hiding the rounded corners
    val tip = Path().apply {
        moveTo(left, tipTop)
        lineTo(left + rodW, tipTop)
        lineTo(cx, h - 4.dp.toPx())
        close()
    }
    drawPath(tip, edge)
    drawRoundRect(cap, Offset(cx - 15.dp.toPx(), top - 9.dp.toPx()), Size(30.dp.toPx(), 16.dp.toPx()),
        CornerRadius(5.dp.toPx()))
}

/** A message on the wire: a bright dot inside a soft halo. */
private fun DrawScope.drawPulse(at: Offset, color: Color) {
    drawCircle(color.copy(alpha = 0.22f), radius = 9.dp.toPx(), center = at)
    drawCircle(color, radius = 3.5.dp.toPx(), center = at)
}

/** The cut wire's ends, marked where the segments were skipped. */
private fun DrawScope.drawBreak(at: Offset, color: Color) {
    val r = 5.dp.toPx()
    drawLine(color, Offset(at.x - r, at.y - r), Offset(at.x + r, at.y + r),
        strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
    drawLine(color, Offset(at.x - r, at.y + r), Offset(at.x + r, at.y - r),
        strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
}
