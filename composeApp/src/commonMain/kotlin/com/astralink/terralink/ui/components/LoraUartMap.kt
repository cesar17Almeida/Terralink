// The header's UART pairs as a map: every pair the Wio-E5 could hang on, which
// two GPIOs it takes, and -- when it is not available -- what already owns them.
// The full 40-pin map does not fit in a dialog, so this is the same board, cropped
// to the six pairs that matter, with the same tones as the pin map screen.
package com.astralink.terralink.ui.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * One candidate landing spot for the module.
 * @param blocked why this pair cannot be used, in the installer's words; null = free.
 */
data class LoraUartPair(
    val tx: Int,
    val rx: Int,
    val uart: String,
    val blocked: String? = null,
)

/** The pairs, drawn as rows of the board: tap one to put the module on it. */
@Composable
fun LoraUartMap(
    pairs: List<LoraUartPair>,
    selected: Pair<Int, Int>?,
    onSelect: (LoraUartPair) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        pairs.forEach { p ->
            UartPairRow(
                pair = p,
                selected = selected != null && selected.first == p.tx && selected.second == p.rx,
                onSelect = { onSelect(p) },
            )
        }
    }
}

@Composable
private fun UartPairRow(pair: LoraUartPair, selected: Boolean, onSelect: () -> Unit) {
    val t = pinTones()
    val free = pair.blocked == null
    val edge = when {
        selected -> t.accent
        free -> t.boardEdge
        else -> t.hairline
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) t.accent.copy(alpha = 0.07f) else Color.Transparent,
                RoundedCornerShape(14.dp),
            )
            .border(BorderStroke(if (selected) 1.5.dp else 1.dp, edge), RoundedCornerShape(14.dp))
            .let { if (free) it.clickable(onClick = onSelect) else it }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PinChip("GP${pair.tx}", "TX", selected, free, t)
            Spacer(Modifier.width(8.dp))
            PinChip("GP${pair.rx}", "RX", selected, free, t)
            Spacer(Modifier.width(12.dp))
            Text(
                pair.uart,
                modifier = Modifier.weight(1f),
                style = TextStyle(
                    fontFamily = Mono, fontSize = 10.sp, letterSpacing = 0.1.em,
                    color = if (free) t.faint else t.sysInk,
                ),
            )
            if (selected) {
                Text(
                    "ELEGIDO",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = t.accent,
                )
            }
        }
        pair.blocked?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = t.sysInk)
        }
    }
}

/** One pin as it looks on the map: the header dot, its GP number and its role. */
@Composable
private fun PinChip(label: String, role: String, selected: Boolean, free: Boolean, t: PinTones) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.alpha(if (free) 1f else 0.55f)) {
        Box(
            Modifier
                .size(11.dp)
                .background(if (selected) t.accent else if (free) t.freeDot else t.sysDot, CircleShape)
                .border(1.dp, if (selected) t.accent else t.freeEdge, CircleShape),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            style = TextStyle(fontFamily = Mono, fontSize = 12.sp, color = t.ink),
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.width(4.dp))
        Box(
            Modifier
                .height(16.dp)
                .background(t.hairline, RoundedCornerShape(4.dp))
                .padding(horizontal = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                role,
                style = TextStyle(fontFamily = Mono, fontSize = 9.sp, letterSpacing = 0.06.em, color = t.muted),
            )
        }
    }
}
