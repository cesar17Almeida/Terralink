package com.astralink.terralink.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.astralink.terralink.ble.protocol.LoraStatus
import com.astralink.terralink.ble.session.ActiveSession
import com.astralink.terralink.state.StationsRepository
import com.astralink.terralink.util.formatRelativeMs
import com.astralink.terralink.util.isStale
import com.astralink.terralink.util.nowMs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Max one on-demand ping (= one LoRaWAN uplink) per this window. */
const val LORA_PING_COOLDOWN_MS: Long = 10 * 60 * 1000L

private enum class PingPhase { ASK, PINGING, RESULT }

/**
 * Confirm + run an on-demand LoRa ping (join + one confirmed uplink), then reveal
 * the resulting signal with an animation. Rate-limited to one ping per
 * [LORA_PING_COOLDOWN_MS] via StationsRepository (persisted per station), so the
 * user can't spam TTN uplinks. [onResult] hands the fresh status back to the caller.
 */
@Composable
fun LoraPingDialog(
    session: ActiveSession,
    bleId: String,
    initial: LoraStatus?,
    onDismiss: () -> Unit,
    onResult: (LoraStatus) -> Unit,
) {
    var phase by remember { mutableStateOf(PingPhase.ASK) }
    var lora by remember { mutableStateOf(initial) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Tick "now" every second so the cooldown countdown stays live.
    var now by remember { mutableStateOf(nowMs()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = nowMs() } }

    val stations by StationsRepository.stations.collectAsStateWithLifecycle()
    val lastPing = stations.firstOrNull { it.bleId == bleId }?.lastLoraPingMs
    val remainingMs = lastPing?.let { (LORA_PING_COOLDOWN_MS - (now - it)).coerceAtLeast(0) } ?: 0L
    val cooling = remainingMs > 0

    fun startPing() {
        StationsRepository.updateLastLoraPing(bleId, nowMs())   // stamp the uplink now
        errorMsg = null
        phase = PingPhase.PINGING
        scope.launch {
            try {
                val beforeSeq = lora?.seq ?: 0
                session.loraPing()
                var settled: LoraStatus? = null
                var unsupported = false
                for (i in 0 until 30) {          // ~45 s: join(12)+uplink(15)+config+wake
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
                errorMsg = when {
                    unsupported -> "Esta estación no soporta diagnóstico LoRa"
                    s == null -> "El ping no terminó a tiempo (reintenta)"
                    !s.inited -> "Módulo sin respuesta — revisa RX/TX y VCC (3V3)"
                    !s.joined -> "Módulo OK, pero sin red TTN (cobertura/credenciales)"
                    else -> null
                }
                if (s != null) { lora = s; onResult(s) }
            } catch (e: Throwable) {
                errorMsg = e.message ?: "Error en el ping"
            } finally {
                phase = PingPhase.RESULT
            }
        }
    }

    val confirmText: String
    val confirmEnabled: Boolean
    val onConfirm: () -> Unit
    val dismissText: String?
    when (phase) {
        PingPhase.ASK -> {
            confirmText = if (cooling) "Espera ${formatMmSs(remainingMs)}" else "Hacer ping"
            confirmEnabled = !cooling
            onConfirm = { startPing() }
            dismissText = "Cancelar"
        }
        PingPhase.PINGING -> {
            confirmText = "Haciendo ping…"
            confirmEnabled = false
            onConfirm = {}
            dismissText = null
        }
        PingPhase.RESULT -> {
            confirmText = "Cerrar"
            confirmEnabled = true
            onConfirm = onDismiss
            dismissText = null
        }
    }

    TerraDialog(
        onDismiss = { if (phase != PingPhase.PINGING) onDismiss() },
        title = "Señal LoRa",
        confirmText = confirmText,
        confirmEnabled = confirmEnabled,
        onConfirm = onConfirm,
        dismissText = dismissText,
    ) {
        Crossfade(targetState = phase, label = "lora-ping") { p ->
            when (p) {
                PingPhase.ASK -> AskContent(lora, cooling, remainingMs)
                PingPhase.PINGING -> PingingContent(lora)
                PingPhase.RESULT -> ResultContent(lora, errorMsg)
            }
        }
    }
}

@Composable
private fun AskContent(lora: LoraStatus?, cooling: Boolean, remainingMs: Long) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LoraSignalIndicator(lora, Modifier.size(56.dp), stale = isStale(lora?.lastMs))
        Spacer(Modifier.height(4.dp))
        Text(
            "Se enviará un uplink a TTN para medir la señal actual del enlace LoRa.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            "Máximo 1 medición cada 10 minutos.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        lora?.lastMs?.let {
            Text(
                "Última medición: ${formatRelativeMs(it)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (cooling) {
            Text(
                "Podrás volver a medir en ${formatMmSs(remainingMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun PingingContent(lora: LoraStatus?) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.15f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "pulse",
    )
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LoraSignalIndicator(lora, Modifier.size(56.dp), reveal = pulse)
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(
            "Enviando uplink y esperando respuesta de TTN… (hasta ~45 s)",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ResultContent(lora: LoraStatus?, errorMsg: String?) {
    // Reveal the waves from 0 -> full when this branch appears.
    var target by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) { target = 1f }
    val reveal by animateFloatAsState(target, tween(900), label = "reveal")

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val joined = lora?.joined == true
        val rssi = lora?.rssi
        val settled = errorMsg == null && rssi != null
        LoraSignalIndicator(lora, Modifier.size(64.dp), reveal = if (settled) reveal else 1f)
        Spacer(Modifier.height(4.dp))
        when {
            errorMsg != null -> Text(
                errorMsg,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            // Joined but no ACK came back: the node has no downlink signal to report.
            joined && rssi == null -> {
                Text(
                    "Conectado a TTN",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Sin lectura de señal: el uplink salió pero no volvió el ACK de bajada " +
                        "(enlace de descenso débil / cobertura marginal). Prueba con mejor antena o posición.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            else -> {
                Text(
                    "Conectado",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                val snr = lora?.snr?.let { " · SNR ${it} dB" } ?: ""
                Text("$rssi dBm$snr", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "medido ahora",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatMmSs(ms: Long): String {
    val total = (ms / 1000).toInt()
    val m = total / 60
    val s = total % 60
    return "$m:${s.toString().padStart(2, '0')}"
}
