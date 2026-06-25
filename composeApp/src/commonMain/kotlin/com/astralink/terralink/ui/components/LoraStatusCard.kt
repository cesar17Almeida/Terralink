package com.astralink.terralink.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.astralink.terralink.ble.protocol.LoraStatus
import com.astralink.terralink.ble.session.ActiveSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Compact LoRa / TTN glance: a one-line verdict + a signal indicator (4 bars when
 * connected, a slashed "no connection" symbol otherwise) + a Ping button. All
 * node-measured over BLE (the phone has no LoRa radio).
 */
@Composable
fun LoraStatusCard(
    session: ActiveSession,
    initial: LoraStatus?,
    modifier: Modifier = Modifier,
) {
    var lora by remember { mutableStateOf(initial) }
    var pinging by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "LoRa · TTN",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = statusLine(lora),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                SignalIndicator(lora, Modifier.size(width = 46.dp, height = 30.dp))
            }

            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    if (!pinging) {
                        pinging = true
                        error = null
                        scope.launch {
                            try {
                                val beforeSeq = lora?.seq ?: 0
                                session.loraPing()
                                var settled: LoraStatus? = null
                                var unsupported = false
                                for (i in 0 until 30) {        // ~45 s: join(12)+uplink(15)+config+wake
                                    delay(1500)
                                    val st = runCatching { session.readStatus() }.getOrNull()
                                    if (st != null) {
                                        val l = st.lora
                                        if (l == null) { unsupported = true; break }
                                        lora = l
                                        if (l.seq != beforeSeq) { settled = l; break }
                                    }
                                }
                                val s = settled
                                error = when {
                                    unsupported -> "Esta estación no soporta diagnóstico LoRa"
                                    s == null -> "El ping no terminó a tiempo (reintenta)"
                                    !s.inited -> "Módulo sin respuesta — revisa RX/TX y VCC (3V3)"
                                    !s.joined -> "Módulo OK, pero sin red TTN (cobertura/credenciales)"
                                    else -> null
                                }
                            } catch (e: Throwable) {
                                error = e.message ?: "Error en el ping"
                            } finally {
                                pinging = false
                            }
                        }
                    }
                },
                enabled = !pinging,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (pinging) "Haciendo ping…" else "Ping TTN")
            }

            error?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

// One terse verdict line.
private fun statusLine(l: LoraStatus?): String = when {
    l == null -> "Sin probar"
    l.joined -> "Conectado" + (l.rssi?.let { " · $it dBm" } ?: "")
    l.inited -> "Módulo OK · sin TTN"
    else -> "Sin respuesta"
}

private fun rssiToBars(rssi: Int?): Int = when {
    rssi == null -> 0
    rssi >= -95 -> 4
    rssi >= -105 -> 3
    rssi >= -115 -> 2
    rssi >= -125 -> 1
    else -> 0
}

// 4 bars when connected; faint bars with a red diagonal slash when there's no link.
@Composable
private fun SignalIndicator(l: LoraStatus?, modifier: Modifier = Modifier) {
    val connected = l?.joined == true && l.rssi != null
    val bars = if (connected) rssiToBars(l?.rssi) else 0
    val active = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val slash = MaterialTheme.colorScheme.error
    Canvas(modifier) {
        val n = 4
        val gap = size.width * 0.06f
        val bw = (size.width - gap * (n - 1)) / n
        for (i in 0 until n) {
            val h = size.height * (0.4f + 0.2f * i)
            drawRoundRect(
                color = if (i < bars) active else muted,
                topLeft = Offset(i * (bw + gap), size.height - h),
                size = Size(bw, h),
                cornerRadius = CornerRadius(bw * 0.25f, bw * 0.25f),
            )
        }
        if (!connected) {   // "no connection" symbol
            drawLine(
                color = slash,
                start = Offset(size.width * 0.06f, size.height * 0.08f),
                end = Offset(size.width * 0.94f, size.height * 0.92f),
                strokeWidth = size.height * 0.10f,
            )
        }
    }
}
