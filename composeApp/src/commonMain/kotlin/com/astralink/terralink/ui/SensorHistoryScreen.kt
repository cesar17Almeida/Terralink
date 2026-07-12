package com.astralink.terralink.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.astralink.terralink.ble.protocol.Reading
import com.astralink.terralink.ble.protocol.SensorInfo
import com.astralink.terralink.ble.session.ActiveSession
import com.astralink.terralink.model.SavedStation
import com.astralink.terralink.state.ReadingsRepository
import com.astralink.terralink.state.StationsRepository
import com.astralink.terralink.ui.components.BackIconButton
import com.astralink.terralink.ui.components.EmptyState
import com.astralink.terralink.ui.components.TerraIcons
import com.astralink.terralink.util.nowMs
import com.astralink.terralink.util.systemUtcOffsetMinutes
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

// Incremental pull tuning, mirroring SyncScreen's paged raw download.
private const val HIST_PAGE_SIZE = 500
private const val HIST_MAX_PAGES = 200
private const val HIST_PAGE_TIMEOUT_MS = 30_000L

/**
 * History of ONE sensor: every reading it produced (all kinds/depths), newest first,
 * with a local date/time and the value + unit. Data is the real station cache
 * (SQLDelight), filtered by the sensor's physical port. "Actualizar" tops the cache up
 * from the station incrementally (from the cached max ts), the same pipeline SyncScreen uses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SensorHistoryScreen(
    station: SavedStation,
    active: ActiveSession,
    sensor: SensorInfo,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var readings by remember { mutableStateOf<List<Reading>>(emptyList()) }
    var count by remember { mutableStateOf(0L) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    val offsetMin = remember { systemUtcOffsetMinutes() }

    suspend fun reloadCache() {
        readings = ReadingsRepository.selectByStationPort(station.bleId, sensor.port)
        count = ReadingsRepository.countByStationPort(station.bleId, sensor.port)
    }

    LaunchedEffect(station.bleId, sensor.port) {
        loading = true
        runCatching { reloadCache() }
        loading = false
    }

    fun refresh() {
        if (refreshing) return
        refreshing = true
        scope.launch {
            val res = runCatching {
                pullIncremental(active, station.bleId)
                reloadCache()
            }
            refreshing = false
            res.exceptionOrNull()?.let {
                snackbarHostState.showSnackbar(it.message ?: "No se pudo actualizar")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Historial del sensor", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { BackIconButton(onClick = onBack) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { HistoryHeader(sensor = sensor, count = count) }
            item {
                Button(
                    onClick = { refresh() },
                    enabled = !refreshing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (refreshing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(if (refreshing) "Actualizando…" else "Actualizar")
                }
            }

            when {
                loading -> item {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                readings.isEmpty() -> item {
                    EmptyState(
                        icon = TerraIcons.Sensors,
                        title = "Sin lecturas de este sensor",
                        hint = "Pulsa Actualizar para descargarlas de la estación.",
                    )
                }
                else -> {
                    item { HistoryColumnHeader() }
                    items(readings.size) { i ->
                        ReadingRow(readings[i], sensor, offsetMin)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryHeader(sensor: SensorInfo, count: Long) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(sensorTypeLabel(sensor.type), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    append("Puerto ${sensor.port} · GPIO ${sensor.gpio}")
                    sensor.gpio2?.let { append(" + $it") }
                    if (sensor.type.startsWith("sdi12") && sensor.addr.isNotBlank()) append(" · addr ${sensor.addr}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (count == 0L) "Sin lecturas guardadas" else "$count lecturas guardadas",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun HistoryColumnHeader() {
    Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        HeaderCell("Fecha y hora", 0.44f)
        HeaderCell("Magnitud", 0.31f)
        HeaderCell("Valor", 0.25f, end = true)
    }
}

@Composable
private fun ReadingRow(r: Reading, sensor: SensorInfo, offsetMin: Int) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = formatLocalDateTime(r.tsMs, offsetMin),
            modifier = Modifier.weight(0.44f),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        Column(modifier = Modifier.weight(0.31f)) {
            Text(kindLabelEs(r.kind), style = MaterialTheme.typography.bodySmall)
            r.depthCm?.takeIf { it > 0 }?.let {
                Text("$it cm", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            text = valueWithUnit(r, sensor),
            modifier = Modifier.weight(0.25f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun RowScope.HeaderCell(label: String, weight: Float, end: Boolean = false) {
    Text(
        text = label,
        modifier = Modifier.weight(weight),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = if (end) TextAlign.End else TextAlign.Start,
    )
}

// --- incremental download (same shape as SyncScreen's runStreamingSync) ------

private suspend fun pullIncremental(active: ActiveSession, stationId: String) {
    val now = nowMs()
    active.setTime(now)
    // Start from just past the newest reading we already cached: incremental top-up.
    var nextFrom = (ReadingsRepository.maxTsByStation(stationId) ?: 0L) + 1
    val all = mutableListOf<Reading>()
    var page = 0
    while (page < HIST_MAX_PAGES) {
        page++
        val pageReadings = withTimeout(HIST_PAGE_TIMEOUT_MS) {
            active.requestRawReadings(fromMs = nextFrom, toMs = now, limit = HIST_PAGE_SIZE)
        }
        if (pageReadings.isEmpty()) break
        all += pageReadings
        nextFrom = pageReadings.last().tsMs + 1
        if (pageReadings.size < HIST_PAGE_SIZE) break
    }
    if (all.isNotEmpty()) {
        ReadingsRepository.insertBatch(stationId, all)
        StationsRepository.updateLastSync(stationId, all.last().tsMs + 1)
    }
}

// --- formatting -------------------------------------------------------------

private fun kindLabelEs(kind: String): String = when (kind) {
    "soil_moisture" -> "Humedad de suelo"
    "soil_temperature" -> "Temp. de suelo"
    "air_temperature" -> "Temp. de aire"
    "air_humidity" -> "Humedad de aire"
    "distance" -> "Distancia"
    "generic" -> "Genérico"
    else -> kind
}

// Value + unit. The sensor slot's own `unit` label wins when present; otherwise the
// unit implied by the reading kind (VWC / °C / %RH / mm).
private fun valueWithUnit(r: Reading, sensor: SensorInfo): String {
    val unit = sensor.unit?.takeIf { it.isNotBlank() } ?: implicitUnit(r.kind)
    val v = formatValue(r.kind, r.value)
    return if (unit.isBlank()) v else "$v $unit"
}

private fun implicitUnit(kind: String): String = when (kind) {
    "soil_moisture" -> "VWC"
    "soil_temperature", "air_temperature" -> "°C"
    "air_humidity" -> "%RH"
    "distance" -> "mm"
    else -> ""
}

private fun formatValue(kind: String, value: Double): String = when (kind) {
    "soil_moisture", "generic" -> fmtDecimals(value, 2)
    "soil_temperature", "air_temperature" -> fmtDecimals(value, 1)
    "air_humidity", "distance" -> fmtDecimals(value, 0)
    else -> fmtDecimals(value, 2)
}

// Fixed-decimals formatter without depending on platform String.format.
private fun fmtDecimals(v: Double, places: Int): String {
    var mul = 1L
    repeat(places) { mul *= 10 }
    val neg = v < 0
    val scaled = kotlin.math.round(kotlin.math.abs(v) * mul).toLong()
    val whole = scaled / mul
    val frac = scaled % mul
    val sign = if (neg && (whole != 0L || frac != 0L)) "-" else ""
    return if (places == 0) "$sign$whole"
           else "$sign$whole.${frac.toString().padStart(places, '0')}"
}

private const val MS_PER_DAY = 86_400_000L

// epoch ms (UTC) + system offset -> "dd/MM HH:mm:ss" in local time, via the
// Howard Hinnant civil-from-days algorithm (no platform date API).
private fun formatLocalDateTime(ms: Long, offsetMin: Int): String {
    // Local instants for any real reading are positive, so plain /,% are safe here.
    val local = ms + offsetMin * 60_000L
    val epochDay = local / MS_PER_DAY
    val msOfDay = local % MS_PER_DAY
    val hh = (msOfDay / 3_600_000L)
    val mm = (msOfDay / 60_000L) % 60
    val ss = (msOfDay / 1000L) % 60
    val z = epochDay + 719_468
    val era = if (z >= 0) z / 146_097 else (z - 146_096) / 146_097
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val mon = if (mp < 10) mp + 3 else mp - 9
    fun p2(n: Long) = n.toString().padStart(2, '0')
    return "${p2(d)}/${p2(mon)} ${p2(hh)}:${p2(mm)}:${p2(ss)}"
}
