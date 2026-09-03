package com.astralink.terralink.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.astralink.terralink.ble.protocol.SensorType
import com.astralink.terralink.ble.session.ActiveSession
import com.astralink.terralink.model.SavedStation
import com.astralink.terralink.sensors.AquaCheckLiveReader
import com.astralink.terralink.sensors.AquaCheckPhase
import com.astralink.terralink.sensors.AquaCheckSample
import com.astralink.terralink.sensors.aquaCheckDepthsCm
import com.astralink.terralink.ui.components.BackIconButton
import com.astralink.terralink.ui.components.EmptyState
import com.astralink.terralink.ui.components.TerraIcons
import com.astralink.terralink.ui.components.soilprobe.DepthRowModel
import com.astralink.terralink.ui.components.soilprobe.LivePill
import com.astralink.terralink.ui.components.soilprobe.PROBE_PERIOD_DEFAULT_S
import com.astralink.terralink.ui.components.soilprobe.SoilProbeCard
import com.astralink.terralink.ui.components.soilprobe.microLabelStyle
import com.astralink.terralink.util.nowMs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** One configured AquaCheck slot the screen can watch. */
private data class ProbeTarget(val port: Int, val gpio: Int, val addr: Char)

private sealed class ProbeConfig {
    data object Loading : ProbeConfig()
    data class Failed(val message: String) : ProbeConfig()
    data object NoProbe : ProbeConfig()
    data class Ready(val probes: List<ProbeTarget>) : ProbeConfig()
}

/** Sensors the 1120-0404 probe has; shown until the first pass tells us otherwise. */
private const val DEFAULT_SENSOR_COUNT = 4

/** Shortest breather between two passes, whatever the period says. */
private const val MIN_REST_S = 2

/**
 * Live view of an AquaCheck probe: the app drives the SDI-12 measurement itself
 * through the station's raw console, one pass after another, starting a pass every
 * `periodS` seconds (one minute by default: the probe does not measure faster and
 * simply repeats its last reading when asked sooner). Nothing is stored -- this is
 * the technician watching the soil while the probe goes in, or while water goes on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoilProbeScreen(
    station: SavedStation,
    active: ActiveSession,
    onOpenSensors: () -> Unit,
    onBack: () -> Unit,
) {
    var config by remember { mutableStateOf<ProbeConfig>(ProbeConfig.Loading) }
    var reloadKey by remember { mutableStateOf(0) }
    var probeIndex by remember { mutableStateOf(0) }

    // Live state, hoisted so the app bar (subtitle + pill) can read it too.
    var sample by remember { mutableStateOf<AquaCheckSample?>(null) }
    var tick by remember { mutableStateOf(0) }
    var readPhase by remember { mutableStateOf<AquaCheckPhase?>(null) }
    var countdown by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var periodS by remember { mutableStateOf(PROBE_PERIOD_DEFAULT_S) }
    val haptics = LocalHapticFeedback.current

    LaunchedEffect(station.bleId, reloadKey) {
        config = ProbeConfig.Loading
        config = try {
            val probes = active.readConfig().sensors
                .filter { it.type == SensorType.SDI12_AQUACHECK }
                .map { ProbeTarget(it.port, it.gpio, it.addr.firstOrNull() ?: '0') }
            if (probes.isEmpty()) ProbeConfig.NoProbe else ProbeConfig.Ready(probes)
        } catch (e: Throwable) {
            ProbeConfig.Failed(e.message ?: "No se pudo leer la configuración de la estación")
        }
    }

    val probes = (config as? ProbeConfig.Ready)?.probes.orEmpty()
    val probe = probes.getOrNull(probeIndex.coerceIn(0, (probes.size - 1).coerceAtLeast(0)))

    // The polling loop. Cancelled on back (the effect leaves composition) and
    // restarted when the technician switches probes.
    LaunchedEffect(probe) {
        if (probe == null) return@LaunchedEffect
        sample = null; tick = 0; error = null; selected = null; countdown = null
        val reader = AquaCheckLiveReader(
            transact = { cmd -> active.sdi12Command(probe.gpio, cmd).lines.firstOrNull().orEmpty() },
            addr = probe.addr,
        )
        while (isActive) {
            readPhase = AquaCheckPhase.MOISTURE_START
            val started = nowMs()
            try {
                val s = reader.read { readPhase = it }
                sample = s
                tick++
                error = null
                // A barely-there tick: the phone confirms a fresh reading without pulling the eye.
                haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e.message ?: "No se pudo leer la sonda"
            }
            readPhase = null
            // The period runs from the start of one pass to the start of the next.
            val elapsedS = ((nowMs() - started) / 1_000L).toInt()
            var left = (periodS - elapsedS).coerceAtLeast(MIN_REST_S)
            while (left > 0) {
                countdown = left
                delay(1_000)
                left--
            }
            countdown = null
        }
    }

    val current = sample
    val focus = selected
    val count = current?.sensorCount ?: DEFAULT_SENSOR_COUNT
    val depths = aquaCheckDepthsCm(count)
    val subtitle = when {
        current != null -> "$count profundidades · ${depths.first()} a ${depths.last()} cm"
        probe != null -> "SDI-12 · GP${probe.gpio} · puerto ${probe.port}"
        else -> "AquaCheck SDI-12"
    }.uppercase()
    val hint = when {
        focus != null -> "${depths.getOrNull(focus) ?: 0} cm seleccionada"
        current != null && current.retries > 0 -> "${current.retries} respuestas repetidas en la última pasada"
        else -> "Toca una profundidad"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Sonda de suelo", fontWeight = FontWeight.SemiBold)
                        Text(subtitle, style = microLabelStyle())
                    }
                },
                navigationIcon = { BackIconButton(onClick = onBack) },
                actions = {
                    if (probe != null) LivePill(live = error == null, modifier = Modifier.padding(end = 12.dp))
                },
            )
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            when (val c = config) {
                ProbeConfig.Loading -> CenteredNote { CircularProgressIndicator(); Text("Buscando la sonda…") }
                is ProbeConfig.Failed -> CenteredNote {
                    Text("No se pudo leer la estación", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(c.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { reloadKey++ }) { Text("Reintentar") }
                }
                ProbeConfig.NoProbe -> NoProbePanel(onOpenSensors)
                is ProbeConfig.Ready -> Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    if (c.probes.size > 1) {
                        ProbeChips(c.probes, probeIndex, onPick = { probeIndex = it })
                    }
                    Spacer(Modifier.height(4.dp))
                    SoilProbeCard(
                        rows = depths.mapIndexed { i, d ->
                            DepthRowModel(d, current?.moisture?.getOrNull(i), current?.temperatureC?.getOrNull(i))
                        },
                        selected = focus,
                        onSelect = { i -> selected = if (selected == i) null else i },
                        status = statusLine(readPhase, countdown, error),
                        statusIsError = readPhase == null && error != null,
                        hint = hint,
                        tick = tick,
                        periodS = periodS,
                        onPeriodChange = { periodS = it },
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(bottom = 16.dp),
                    )
                }
            }
        }
    }
}

/** What the footer says: the pass in progress beats a stale error, the error beats the countdown. */
private fun statusLine(phase: AquaCheckPhase?, countdown: Int?, error: String?): String = when {
    phase != null -> when (phase) {
        AquaCheckPhase.MOISTURE_START -> "Pidiendo humedad a la sonda…"
        AquaCheckPhase.MOISTURE_WAIT -> "La sonda está midiendo…"
        AquaCheckPhase.MOISTURE_READ -> "Leyendo humedad…"
        AquaCheckPhase.TEMPERATURE_START, AquaCheckPhase.TEMPERATURE_READ -> "Leyendo temperatura…"
    }
    error != null -> error
    countdown != null -> "Siguiente lectura en $countdown s"
    else -> "Lista"
}

@Composable
private fun ProbeChips(probes: List<ProbeTarget>, selected: Int, onPick: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        probes.forEachIndexed { i, p ->
            FilterChip(
                selected = i == selected,
                onClick = { onPick(i) },
                label = { Text("Puerto ${p.port} · GP${p.gpio}") },
            )
        }
    }
}

@Composable
private fun NoProbePanel(onOpenSensors: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        EmptyState(
            icon = TerraIcons.Layers,
            title = "Sin sonda AquaCheck",
            hint = "Añade una sonda AquaCheck para verla en vivo.",
            modifier = Modifier.padding(bottom = 20.dp),
        )
        Button(onClick = onOpenSensors) { Text("Ir a sensores") }
    }
}

@Composable
private fun CenteredNote(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) { content() }
}
