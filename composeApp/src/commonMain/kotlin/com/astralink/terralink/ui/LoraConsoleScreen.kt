package com.astralink.terralink.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.astralink.terralink.ble.session.ActiveSession
import com.astralink.terralink.ui.components.BackIconButton
import com.astralink.terralink.ui.components.TerraIcons
import com.astralink.terralink.ui.components.TerraTextField
import kotlinx.coroutines.launch

private data class AtBubble(val fromUser: Boolean, val text: String, val isError: Boolean = false)

private val QUICK_CMDS = listOf("AT", "AT+VER", "AT+ID", "AT+MODE=LWOTAA", "AT+DR=EU868", "AT+JOIN")

/** Chat-style raw AT terminal: the phone sends a command over BLE, the Pico relays
 *  it to the Wio-E5 over UART and returns the reply lines, shown as chat bubbles. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoraConsoleScreen(
    active: ActiveSession,
    onBack: () -> Unit,
) {
    val bubbles = remember { mutableStateListOf<AtBubble>() }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    fun send(cmd: String) {
        val c = cmd.trim()
        if (c.isEmpty() || sending) return
        bubbles.add(AtBubble(fromUser = true, text = c))
        input = ""
        sending = true
        scope.launch {
            try {
                val res = active.atCommand(c)
                val reply = res.lines.joinToString("\n").ifBlank { "(sin respuesta)" }
                bubbles.add(AtBubble(fromUser = false, text = reply))
            } catch (e: Throwable) {
                bubbles.add(AtBubble(fromUser = false, text = e.message ?: "Error", isError = true))
            } finally {
                sending = false
                listState.animateScrollToItem(bubbles.lastIndex.coerceAtLeast(0))
            }
        }
        scope.launch { listState.animateScrollToItem(bubbles.lastIndex.coerceAtLeast(0)) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Consola LoRa", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) },
                navigationIcon = { BackIconButton(onClick = onBack) },
            )
        },
    ) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            // Conversation
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
            ) {
                if (bubbles.isEmpty()) {
                    item {
                        Text(
                            text = "Envía un comando AT al módulo Wio-E5 (p. ej. AT, AT+VER, AT+JOIN). " +
                                "El móvil lo manda por BLE; la estación lo reenvía por UART y muestra la respuesta.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                items(bubbles.size) { i -> Bubble(bubbles[i]) }
                if (sending) {
                    item {
                        Text(
                            text = "…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Quick commands
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QUICK_CMDS.forEach { q ->
                    AssistChip(onClick = { send(q) }, label = { Text(q) })
                }
            }

            // Input
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TerraTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = "Comando AT",
                    enabled = !sending,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { send(input) }, enabled = !sending && input.isNotBlank()) {
                    Icon(
                        imageVector = TerraIcons.Sync,
                        contentDescription = "Enviar",
                        tint = if (!sending && input.isNotBlank()) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Bubble(b: AtBubble) {
    val bg = when {
        b.isError -> MaterialTheme.colorScheme.errorContainer
        b.fromUser -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = when {
        b.isError -> MaterialTheme.colorScheme.onErrorContainer
        b.fromUser -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (b.fromUser) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Surface(
            color = bg,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Text(
                text = b.text,
                style = MaterialTheme.typography.bodyMedium,
                color = fg,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}
