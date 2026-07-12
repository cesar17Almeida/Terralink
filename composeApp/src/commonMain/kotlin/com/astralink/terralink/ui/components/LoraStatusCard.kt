package com.astralink.terralink.ui.components

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
                LoraSignalIndicator(lora, Modifier.size(32.dp))
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

