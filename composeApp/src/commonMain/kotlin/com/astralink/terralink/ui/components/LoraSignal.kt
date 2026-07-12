package com.astralink.terralink.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.astralink.terralink.ble.protocol.LoraStatus
import com.astralink.terralink.util.formatRelativeMs
import com.astralink.terralink.util.isStale
import kotlin.math.roundToInt

internal fun loraRssiToBars(rssi: Int?): Int = when {
    rssi == null -> 0
    rssi >= -95 -> 4
    rssi >= -105 -> 3
    rssi >= -115 -> 2
    rssi >= -125 -> 1
    else -> 0
}

/**
 * LoRa link as concentric radio waves: an emitter dot plus three arcs that light up
 * with the node-measured downlink RSSI when joined; a red diagonal slash means "no
 * link". The phone has no LoRa radio, so this is whatever the station last reported.
 * [reveal] 0..1 animates how many waves are lit (used for the ping-result reveal);
 * [stale] fades the active colour when the last reading is old.
 */
@Composable
fun LoraSignalIndicator(
    lora: LoraStatus?,
    modifier: Modifier = Modifier,
    reveal: Float = 1f,
    stale: Boolean = false,
) {
    val rssi = lora?.rssi
    val connected = lora?.joined == true && rssi != null
    val fullBars = if (connected) loraRssiToBars(rssi) else 0
    val bars = (fullBars * reveal.coerceIn(0f, 1f)).roundToInt()
    val baseActive = MaterialTheme.colorScheme.primary
    val active = if (stale) baseActive.copy(alpha = 0.4f) else baseActive
    val muted = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val slash = MaterialTheme.colorScheme.error
    Canvas(modifier) {
        val s = size.minDimension
        val origin = Offset(s * 0.16f, size.height - s * 0.16f)
        drawCircle(   // emitter (level 1)
            color = if (bars >= 1) active else muted,
            radius = s * 0.07f,
            center = origin,
        )
        for (i in 1..3) {   // expanding waves (levels 2..4)
            val r = s * (0.14f + 0.22f * i)
            drawArc(
                color = if (bars >= i + 1) active else muted,
                startAngle = -90f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(origin.x - r, origin.y - r),
                size = Size(r * 2f, r * 2f),
                style = Stroke(width = s * 0.08f, cap = StrokeCap.Round),
            )
        }
        if (!connected && reveal >= 0.99f) {   // "no link" slash (only once settled)
            drawLine(
                color = slash,
                start = Offset(s * 0.12f, s * 0.12f),
                end = Offset(s * 0.88f, s * 0.88f),
                strokeWidth = s * 0.10f,
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * Minimal home glance: the signal bars + "LoRa" caption + the age of the last
 * reading ("hace 30 min"), faded when stale. [onClick] (open the ping modal) makes
 * the whole glance tappable.
 */
@Composable
fun LoraSignalGlance(
    lora: LoraStatus?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val clickable = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier.then(clickable)) {
        LoraSignalIndicator(lora, Modifier.size(26.dp), stale = isStale(lora?.lastMs))
        Spacer(Modifier.height(3.dp))
        Text(
            text = "LoRa",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        lora?.lastMs?.let {
            Text(
                text = formatRelativeMs(it),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
