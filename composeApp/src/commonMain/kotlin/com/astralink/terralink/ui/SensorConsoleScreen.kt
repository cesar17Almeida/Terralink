package com.astralink.terralink.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.astralink.terralink.ble.session.ActiveSession
import com.astralink.terralink.state.AtLogEntry
import com.astralink.terralink.state.ConsoleChannel
import com.astralink.terralink.state.LoraConsoleRepository
import com.astralink.terralink.ui.components.BackIconButton
import com.astralink.terralink.ui.components.TerraIcons
import com.astralink.terralink.ui.components.TerraTextField
import com.astralink.terralink.ui.components.dismissKeyboardOnTap
import com.astralink.terralink.util.nowEpochMs
import kotlinx.coroutines.launch

private data class ProbeBubble(val tsMs: Long, val fromUser: Boolean, val text: String, val isError: Boolean = false)

// One configured SDI-12 probe the console can target (from the config snapshot).
private data class Sdi12Probe(val port: Int, val gpio: Int, val addr: String, val type: String)

/** Chat-style raw SDI-12 console: pick a configured probe from a dropdown, send a raw
 *  SDI-12 command; the station bit-bangs it on that pin and returns the reply lines.
 *  Mirrors [LoraConsoleScreen] but over the "sdi12" op + its own persisted channel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SensorConsoleScreen(
    active: ActiveSession,
    stationId: String,
    onAddSensor: () -> Unit,
    onBack: () -> Unit,
) {
    val bubbles = remember { mutableStateListOf<ProbeBubble>() }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var probes by remember { mutableStateOf<List<Sdi12Probe>>(emptyList()) }
    var selected by remember { mutableStateOf<Sdi12Probe?>(null) }
    var manualGpio by remember { mutableStateOf("") }
    var advancedOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Configured SDI-12 probes + the persisted history, on first open.
    LaunchedEffect(stationId) {
        val cfg = runCatching { active.readConfig() }.getOrNull()
        probes = cfg?.sensors.orEmpty()
            .filter { it.type.startsWith("sdi12") }
            .map { Sdi12Probe(port = it.port, gpio = it.gpio, addr = it.addr.ifBlank { "0" }, type = it.type) }
        selected = probes.firstOrNull()
        if (bubbles.isEmpty()) {
            LoraConsoleRepository.history(stationId, ConsoleChannel.SDI12).forEach {
                bubbles.add(ProbeBubble(it.tsMs, it.fromUser, it.body, it.isError))
            }
            if (bubbles.isNotEmpty()) listState.scrollToItem(bubbles.lastIndex)
        }
    }

    // A typed GPIO (advanced) overrides the selected probe; otherwise use the probe's pin.
    val manualInt = manualGpio.trim().toIntOrNull()
    val usingManual = advancedOpen && manualInt != null
    val effectiveGpio: Int? = if (usingManual) manualInt else selected?.gpio
    val addr: String = if (usingManual) "0" else (selected?.addr ?: "0")

    fun log(b: ProbeBubble) {
        bubbles.add(b)
        LoraConsoleRepository.append(stationId, AtLogEntry(b.tsMs, b.fromUser, b.text, b.isError), ConsoleChannel.SDI12)
    }

    fun send(cmd: String) {
        val c = cmd.trim()
        val gpio = effectiveGpio
        if (c.isEmpty() || sending || gpio == null) return
        log(ProbeBubble(nowEpochMs(), fromUser = true, text = c))
        input = ""
        sending = true
        scope.launch {
            try {
                val res = active.sdi12Command(gpio, c)
                val reply = res.lines.joinToString("\n").ifBlank { "(sin respuesta)" }
                log(ProbeBubble(nowEpochMs(), fromUser = false, text = reply))
            } catch (e: Throwable) {
                log(ProbeBubble(nowEpochMs(), fromUser = false, text = e.message ?: "Error", isError = true))
            } finally {
                sending = false
                listState.animateScrollToItem(bubbles.lastIndex.coerceAtLeast(0))
            }
        }
        scope.launch { listState.animateScrollToItem(bubbles.lastIndex.coerceAtLeast(0)) }
    }

    // SDI-12 quick commands use the target's address (identify / measure / data).
    val quick = listOf("${addr}I!", "${addr}M!", "${addr}D0!", "?!", "${addr}V!")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Consola de sonda", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { BackIconButton(onClick = onBack) },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .imePadding()
                .dismissKeyboardOnTap(),
        ) {
            // Target picker: a dropdown of configured SDI-12 probes (or an empty state
            // that leads to the wizard), plus a collapsed advanced manual-GPIO option.
            Sdi12Target(
                probes = probes,
                selected = selected,
                onSelect = { selected = it; advancedOpen = false; manualGpio = "" },
                manualGpio = manualGpio,
                onManualChange = { manualGpio = it.filter { c -> c.isDigit() } },
                advancedOpen = advancedOpen,
                onToggleAdvanced = { advancedOpen = !advancedOpen },
                onAddSensor = onAddSensor,
            )

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
                            text = "Habla directamente con una sonda SDI-12: elige la sonda arriba y envía un " +
                                "comando (p. ej. 0I! identificar, 0M! medir, 0D0! leer datos). El móvil lo manda " +
                                "por BLE; la estación lo bit-banguea en el pin y muestra la respuesta.",
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
                quick.forEach { q ->
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
                    label = if (effectiveGpio != null) "Comando SDI-12" else "Elige una sonda primero",
                    enabled = !sending && effectiveGpio != null,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { send(input) },
                    enabled = !sending && input.isNotBlank() && effectiveGpio != null,
                ) {
                    Icon(
                        imageVector = TerraIcons.Send,
                        contentDescription = "Enviar",
                        tint = if (!sending && input.isNotBlank() && effectiveGpio != null)
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Sdi12Target(
    probes: List<Sdi12Probe>,
    selected: Sdi12Probe?,
    onSelect: (Sdi12Probe) -> Unit,
    manualGpio: String,
    onManualChange: (String) -> Unit,
    advancedOpen: Boolean,
    onToggleAdvanced: () -> Unit,
    onAddSensor: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (probes.isEmpty()) EmptyProbes(onAddSensor)
        else ProbeDropdown(probes, selected, onSelect)

        AdvancedManualGpio(manualGpio, onManualChange, advancedOpen, onToggleAdvanced)
    }
}

@Composable
private fun EmptyProbes(onAddSensor: () -> Unit) {
    Card(shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Sin sondas SDI-12", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Configura un sensor SDI-12 primero para hablar con él desde aquí.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onAddSensor, modifier = Modifier.fillMaxWidth()) {
                Icon(TerraIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Añadir sensor SDI-12")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProbeDropdown(
    probes: List<Sdi12Probe>,
    selected: Sdi12Probe?,
    onSelect: (Sdi12Probe) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        // Anchor: a slim outlined row showing the current probe.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                .clickable { expanded = true }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = selected?.let { probeLabel(it) } ?: "Elige una sonda",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = TerraIcons.ExpandMore,
                contentDescription = "Elegir sonda",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            probes.forEach { p ->
                val isSel = selected != null && p.port == selected.port && p.gpio == selected.gpio
                DropdownMenuItem(
                    text = { Text(probeLabel(p)) },
                    onClick = { onSelect(p); expanded = false },
                    trailingIcon = if (isSel) {
                        {
                            Icon(
                                TerraIcons.Check, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp),
                            )
                        }
                    } else null,
                )
            }
        }
    }
}

@Composable
private fun AdvancedManualGpio(
    manualGpio: String,
    onManualChange: (String) -> Unit,
    advancedOpen: Boolean,
    onToggleAdvanced: () -> Unit,
) {
    Text(
        text = if (advancedOpen) "Avanzado ▴" else "Avanzado ▾",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clickable(onClick = onToggleAdvanced),
    )
    if (advancedOpen) {
        TerraTextField(
            value = manualGpio,
            onValueChange = onManualChange,
            label = "GPIO manual",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            supportingText = { Text("Habla con una sonda no configurada indicando su pin de datos.") },
        )
    }
}

private fun probeLabel(p: Sdi12Probe): String =
    "Puerto ${p.port} — ${sensorTypeLabel(p.type)} (GP${p.gpio})"

@Composable
private fun Bubble(b: ProbeBubble) {
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
        Column(horizontalAlignment = if (b.fromUser) Alignment.End else Alignment.Start) {
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
            Text(
                text = clockUtc(b.tsMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, start = 6.dp, end = 6.dp),
            )
        }
    }
}

// HH:mm:ss (UTC) from epoch ms, without a kotlinx-datetime dependency.
private fun clockUtc(ms: Long): String {
    val s = (ms / 1000) % 86400
    fun p(n: Long) = n.toString().padStart(2, '0')
    return "${p(s / 3600)}:${p((s % 3600) / 60)}:${p(s % 60)}"
}
