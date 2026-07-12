package com.astralink.terralink.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.astralink.terralink.ble.protocol.PinmapMsg

/**
 * Read-only overview of the station's whole GPIO allocation (sensors + LoRa +
 * system), so the pin context is visible app-wide. Same source of truth (the
 * device pinmap, ...0015) the sensor wizard uses to block taken pins. Browse mode:
 * nothing is eligible or tappable.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PinMapCard(pinmap: PinmapMsg?, modifier: Modifier = Modifier) {
    val cells = remember(pinmap) {
        val header = picoWHeader()
        if (pinmap == null) header
        else {
            val live = HashMap<Int, Triple<PinLive, String?, Int?>>()
            for (p in pinmap.pins) {
                val state = when (p.state) {
                    "in_use" -> PinLive.IN_USE
                    "reserved" -> PinLive.RESERVED
                    else -> PinLive.FREE
                }
                live[p.gpio] = Triple(state, p.reason.ifBlank { null }, p.port)
            }
            mergePinmap(header, live)
        }
    }

    Card(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(modifier = Modifier.padding(20.dp)) {
            SectionHeader("Mapa de pines", accent = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Text(
                "Asignación real de GPIO de la estación. Los pines en uso por sensores o " +
                    "periféricos no se ofrecen al añadir un sensor.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                PicoPinout(
                    cells = cells,
                    needCaps = 0,           // browse only: no eligibility highlight, no taps
                    onSelect = {},
                    modifier = Modifier.fillMaxWidth(0.66f),
                )
            }
            Spacer(Modifier.height(12.dp))
            PinLegend()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PinLegend() {
    val cs = MaterialTheme.colorScheme
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LegendItem(cs.primary, "En uso (sensor/periférico)")
        LegendItem(cs.surfaceVariant, "Reservado por el sistema")
        LegendItem(cs.surface, "Libre")
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(color, CircleShape),
        )
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
