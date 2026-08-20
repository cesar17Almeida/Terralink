package com.astralink.terralink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.astralink.terralink.ble.session.SaviaSession
import com.astralink.terralink.model.SavedStation
import com.astralink.terralink.state.StationsRepository
import com.astralink.terralink.util.hasFreshClock
import com.astralink.terralink.util.nowMs
import com.astralink.terralink.util.stationClockText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.withTimeoutOrNull

private const val PRESENCE_SCAN_MS = 4_000L
// Cold start: the BLE adapter (and its permission prompt) often isn't ready the
// instant this screen appears, so the first sweep comes back empty with nothing
// actually wrong. Sweep again before declaring every station out of range.
private const val PRESENCE_SCAN_TRIES = 2
private const val PRESENCE_RETRY_MS = 1_200L
private val AvailableColor = Color(0xFF22C55E)
private val UnavailableColor = Color(0xFF9CA3AF)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationsListScreen(
    session: SaviaSession,
    onAddStation: () -> Unit,
    onOpenStation: (SavedStation) -> Unit,
) {
    val stations by StationsRepository.stations.collectAsStateWithLifecycle()
    var seenIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var refreshing by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableStateOf(0) }
    var scanError by remember { mutableStateOf<String?>(null) }
    // Ticks every second so the per-station board clocks advance live.
    var clockNowMs by remember { mutableStateOf(nowMs()) }
    LaunchedEffect(Unit) {
        while (true) { delay(1_000); clockNowMs = nowMs() }
    }

    // Quick presence scan: every time we land here or pull-to-refresh, listen
    // for SaviaServiceUUID advertisements for PRESENCE_SCAN_MS and remember
    // which ids showed up. Stations in `seenIds` get the green "Disponible"
    // dot; the rest are "Fuera de alcance".
    LaunchedEffect(refreshTick) {
        refreshing = true
        var failure: Throwable? = null
        var found: Set<String> = emptySet()
        for (attempt in 0 until PRESENCE_SCAN_TRIES) {
            val collected = mutableSetOf<String>()
            failure = null
            withTimeoutOrNull(PRESENCE_SCAN_MS) {
                session.scan(saviaOnly = true)
                    .catch { failure = it }          // reported below, not swallowed
                    .collect { device ->
                        collected.add(device.id)
                        seenIds = seenIds + device.id     // light them up as they answer
                    }
            }
            found = collected
            if (collected.isNotEmpty()) break              // something answered: done
            if (attempt < PRESENCE_SCAN_TRIES - 1) delay(PRESENCE_RETRY_MS)
        }
        seenIds = found
        scanError = failure?.let {
            "No se pudieron buscar estaciones: ${it.message ?: "error de Bluetooth"}"
        }
        refreshing = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(text = "TerraLink", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "Mis estaciones",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddStation) {
                Text("+", style = MaterialTheme.typography.titleLarge)
            }
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { refreshTick++ },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (stations.isEmpty()) {
                EmptyStationsState()
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // A failed sweep used to be indistinguishable from "nothing
                    // around": name it, since it usually means Bluetooth is off or
                    // the permission was denied.
                    scanError?.let { msg ->
                        item {
                            Text(
                                "$msg Desliza hacia abajo para reintentar.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                        }
                    }
                    items(stations, key = { it.bleId }) { station ->
                        StationRow(
                            station = station,
                            available = station.bleId in seenIds || hasFreshClock(station, clockNowMs),
                            nowTickMs = clockNowMs,
                            onClick = { onOpenStation(station) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyStationsState() {
    Box(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Aún no tienes estaciones",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Toca + para emparejar una estación Savia.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Desliza hacia abajo para refrescar el estado.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StationRow(
    station: SavedStation,
    available: Boolean,
    nowTickMs: Long,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = station.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = station.bleId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                stationClockText(station, nowTickMs)?.let { clock ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = clock,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            AvailabilityIndicator(available = available)
        }
    }
}

@Composable
private fun AvailabilityIndicator(available: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(
                    color = if (available) AvailableColor else UnavailableColor,
                    shape = CircleShape,
                ),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = if (available) "Disponible" else "Fuera de alcance",
            style = MaterialTheme.typography.labelMedium,
            color = if (available) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
