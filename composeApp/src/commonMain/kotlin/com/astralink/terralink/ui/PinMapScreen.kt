package com.astralink.terralink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.astralink.terralink.ble.protocol.PinmapMsg
import com.astralink.terralink.ble.protocol.SensorInfo
import com.astralink.terralink.ble.session.ActiveSession
import com.astralink.terralink.ui.components.Mono
import com.astralink.terralink.ui.components.PinCell
import com.astralink.terralink.ui.components.PinDetailBar
import com.astralink.terralink.ui.components.PinHeaderList
import com.astralink.terralink.ui.components.PinLive
import com.astralink.terralink.ui.components.PinRole
import com.astralink.terralink.ui.components.PinState
import com.astralink.terralink.ui.components.livePins
import com.astralink.terralink.ui.components.mergePinmap
import com.astralink.terralink.ui.components.picoWHeader
import com.astralink.terralink.ui.components.pinState
import com.astralink.terralink.ui.components.pinTones

private sealed class PinMapPhase {
    data object Loading : PinMapPhase()
    data class Ready(val pinmap: PinmapMsg?, val sensors: List<SensorInfo>) : PinMapPhase()
    data class Failed(val message: String) : PinMapPhase()
}

private val FILTERS: List<Pair<String, PinState?>> = listOf(
    "Todos" to null,
    "Libres" to PinState.FREE,
    "En uso" to PinState.USED,
    "Sistema" to PinState.SYSTEM,
)

/** Full-screen view of the station's GPIO allocation. Same source of truth (the
 *  device pinmap, ...0015) the sensor wizard uses to block taken pins. */
@Composable
fun PinMapScreen(active: ActiveSession, stationName: String, onBack: () -> Unit) {
    var phase by remember { mutableStateOf<PinMapPhase>(PinMapPhase.Loading) }
    var reloadKey by remember { mutableStateOf(0) }
    var filter by remember { mutableStateOf<PinState?>(null) }
    var selected by remember { mutableStateOf<Int?>(null) }   // physical pin

    LaunchedEffect(reloadKey) {
        phase = PinMapPhase.Loading
        phase = try {
            val pinmap = active.readPinmap()
            // Sensor names are a nicety on top of the pinmap: a config read that
            // fails must not take the whole screen down with it.
            val sensors = runCatching { active.readConfig().sensors }.getOrDefault(emptyList())
            PinMapPhase.Ready(pinmap, sensors)
        } catch (e: Throwable) {
            PinMapPhase.Failed(e.message ?: "No se pudo leer el mapa de pines")
        }
    }

    val ready = phase as? PinMapPhase.Ready
    val cells = remember(ready) {
        val header = picoWHeader()
        ready?.pinmap?.let { mergePinmap(header, it.livePins()) } ?: header
    }
    val selectedCell = cells.firstOrNull { it.physical == selected }

    Scaffold(
        bottomBar = {
            if (ready != null) {
                PinDetailBar(
                    cell = selectedCell,
                    detail = selectedCell?.let { detailOf(it, ready.sensors) } ?: "",
                    onClear = { selected = null },
                )
            }
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when (val p = phase) {
                PinMapPhase.Loading -> Centered { CircularProgressIndicator() }
                is PinMapPhase.Failed -> Centered {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No se pudo cargar", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Text(p.message, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(20.dp))
                        Button(onClick = { reloadKey++ }) { Text("Reintentar") }
                    }
                }
                is PinMapPhase.Ready -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                ) {
                    Column(Modifier.padding(horizontal = 22.dp)) {
                        PinMapHeader(stationName, onBack)
                        PinCounters(cells)
                        Spacer(Modifier.height(20.dp))
                        FilterChips(filter) { filter = it }
                    }
                    Spacer(Modifier.height(34.dp))
                    PinHeaderList(
                        cells = cells,
                        selected = selected,
                        onSelect = { c -> selected = if (selected == c.physical) null else c.physical },
                        modifier = Modifier.padding(horizontal = 22.dp),
                        isVisible = { filter == null || it.pinState() == filter },
                    )
                    Spacer(Modifier.height(30.dp))
                }
            }
        }
    }
}

@Composable
private fun PinMapHeader(stationName: String, onBack: () -> Unit) {
    val t = pinTones()
    Row(Modifier.height(34.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .offset(x = (-8).dp)
                .size(28.dp)
                .clip(CircleShape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Text("‹", fontSize = 28.sp, color = t.ink)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            "ESTACIÓN · ${stationName.uppercase()}",
            style = TextStyle(
                fontFamily = Mono, fontSize = 9.sp, letterSpacing = 0.16.em, color = t.faint,
            ),
        )
    }
    Spacer(Modifier.height(16.dp))
    Text(
        "Mapa de pines",
        style = TextStyle(
            fontSize = 30.sp, fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.025).em, lineHeight = 33.sp, color = t.ink,
        ),
    )
    Spacer(Modifier.height(10.dp))
    Text(
        "Asignación real de GPIO de la estación. Los pines en uso por sensores o " +
            "periféricos no se ofrecen al añadir un sensor.",
        modifier = Modifier.widthIn(max = 296.dp),
        style = TextStyle(fontSize = 12.5.sp, lineHeight = 20.sp, color = t.muted),
    )
}

@Composable
private fun PinCounters(cells: List<PinCell>) {
    val t = pinTones()
    val gpio = cells.filter { it.role == PinRole.GPIO }
    val free = gpio.count { it.live == PinLive.FREE }
    val used = gpio.count { it.live == PinLive.IN_USE }
    val sys = cells.size - free - used

    Column(Modifier.fillMaxWidth().padding(top = 26.dp)) {
        HorizontalDivider(color = t.hairline)
        Row(
            Modifier.padding(vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Counter(free, "Libres", t.ink, Modifier.weight(1f))
            Box(Modifier.width(1.dp).height(30.dp).background(t.hairline))
            Counter(used, "En uso", t.accent, Modifier.weight(1f).padding(start = 16.dp))
            Box(Modifier.width(1.dp).height(30.dp).background(t.hairline))
            Counter(sys, "Sistema", t.sysInk, Modifier.weight(1f).padding(start = 16.dp))
        }
        HorizontalDivider(color = t.hairline)
    }
}

@Composable
private fun Counter(value: Int, label: String, color: Color, modifier: Modifier = Modifier) {
    val t = pinTones()
    Column(modifier) {
        Text(
            value.toString(),
            style = TextStyle(
                fontFamily = Mono, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.02).em, color = color,
            ),
        )
        Spacer(Modifier.height(5.dp))
        Text(
            label.uppercase(),
            style = TextStyle(
                fontFamily = Mono, fontSize = 8.5.sp, letterSpacing = 0.14.em, color = t.faint,
            ),
        )
    }
}

@Composable
private fun FilterChips(selected: PinState?, onSelect: (PinState?) -> Unit) {
    val t = pinTones()
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        FILTERS.forEach { (label, state) ->
            val on = state == selected
            val shape = RoundedCornerShape(percent = 50)
            Box(
                Modifier
                    .height(31.dp)
                    .background(if (on) t.accent else Color.Transparent, shape)
                    .border(1.dp, if (on) t.accent else t.hairline, shape)
                    .clickable { onSelect(state) }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label.uppercase(),
                    style = TextStyle(
                        fontFamily = Mono, fontSize = 8.5.sp, letterSpacing = 0.12.em,
                        color = if (on) MaterialTheme.colorScheme.onPrimary else t.muted,
                    ),
                )
            }
        }
    }
}

/** One line saying what holds this pin, in the installer's words. */
private fun detailOf(cell: PinCell, sensors: List<SensorInfo>): String = when {
    cell.role != PinRole.GPIO -> cell.fns
    cell.live == PinLive.RESERVED -> when (cell.reason) {
        "wireless" -> "Lo usa la radio de la placa"
        "wake_btn" -> "Botón de encendido de la estación"
        "lora_uart" -> "Puerto serie del módulo LoRa"
        else -> "Reservado por el sistema"
    }
    cell.live == PinLive.IN_USE -> {
        val s = sensors.firstOrNull { it.gpio == cell.gpio || it.gpio2 == cell.gpio }
        when {
            s != null -> "${sensorTypeLabel(s.type)} · Puerto ${s.port}"
            cell.port != null -> "Sensor del puerto ${cell.port}"
            else -> "Ocupado por un periférico"
        }
    }
    else -> "Disponible para asignar un sensor"
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { content() }
}
