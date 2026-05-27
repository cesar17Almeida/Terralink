package com.astralink.terralink.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.astralink.terralink.ble.BleError
import com.astralink.terralink.ble.protocol.StatusMsg
import com.astralink.terralink.ble.session.ActiveSession
import com.astralink.terralink.ble.session.SaviaSession
import com.astralink.terralink.model.SavedStation
import com.astralink.terralink.state.StationsRepository
import com.astralink.terralink.ui.components.ConnectionStatusChip
import com.astralink.terralink.util.nowMs
import kotlinx.coroutines.launch

private const val DEFAULT_LOOKBACK_MS = 24L * 60 * 60 * 1000  // 24h
private const val SYNC_LIMIT = 500                              // rows per request

private sealed class ConnState {
    data object Connecting : ConnState()
    data class Ready(val session: ActiveSession, val status: StatusMsg) : ConnState()
    data class Failed(val message: String) : ConnState()
}

private sealed class SyncState {
    data object Idle : SyncState()
    data object Running : SyncState()
    data class Done(val count: Int, val atMs: Long, val more: Boolean) : SyncState()
    data class Failed(val message: String) : SyncState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(
    station: SavedStation,
    session: SaviaSession,
    onUpdateFirmware: (ActiveSession) -> Unit,
    onBack: () -> Unit,
) {
    var state by remember { mutableStateOf<ConnState>(ConnState.Connecting) }
    var retryKey by remember { mutableStateOf(0) }

    // Re-read the saved station from the repo so updateLastSync() shows up
    // in the UI without needing to navigate back and forth.
    val stations by StationsRepository.stations.collectAsStateWithLifecycle()
    val currentStation = stations.firstOrNull { it.bleId == station.bleId } ?: station

    LaunchedEffect(currentStation.bleId, retryKey) {
        state = ConnState.Connecting
        state = try {
            val active = session.connect(currentStation.bleId)
            val status = active.readStatus()
            ConnState.Ready(active, status)
        } catch (e: BleError) {
            ConnState.Failed(e.message ?: e::class.simpleName ?: "connection failed")
        } catch (e: Throwable) {
            ConnState.Failed(e.message ?: e::class.simpleName ?: "unexpected error")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(text = currentStation.displayName, fontWeight = FontWeight.SemiBold)
                },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Atrás") }
                },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (val s = state) {
                ConnState.Connecting -> ConnectingPanel(currentStation)
                is ConnState.Failed -> FailedPanel(
                    message = s.message,
                    onRetry = { retryKey++ },
                )
                is ConnState.Ready -> ReadyPanel(
                    station = currentStation,
                    status = s.status,
                    active = s.session,
                    onUpdateFirmware = { onUpdateFirmware(s.session) },
                )
            }
        }
    }
}

@Composable
private fun ConnectingPanel(station: SavedStation) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Conectando con ${station.displayName}...",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun FailedPanel(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "No se pudo conectar",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRetry) { Text("Reintentar") }
    }
}

@Composable
private fun ReadyPanel(
    station: SavedStation,
    status: StatusMsg,
    active: ActiveSession,
    onUpdateFirmware: () -> Unit,
) {
    var syncState by remember { mutableStateOf<SyncState>(SyncState.Idle) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = "Estado",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        DeviceStatusCard(station = station, status = status)

        Spacer(Modifier.height(24.dp))

        Button(onClick = onUpdateFirmware, modifier = Modifier.fillMaxWidth()) {
            Text("Actualizar firmware")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                scope.launch { runSync(active, station, onState = { syncState = it }) }
            },
            enabled = syncState !is SyncState.Running,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (syncState is SyncState.Running) "Sincronizando..." else "Sincronizar datos")
        }

        Spacer(Modifier.height(12.dp))
        SyncStatusBanner(state = syncState)
    }
}

/**
 * Runs the sync flow: push the wall clock to the Pi, pull readings since
 * the last sync (or 24h back if first time), and persist the new mark.
 * Errors are surfaced via `onState(SyncState.Failed)`; the connection is
 * not torn down -- the user can retry without re-pairing.
 */
private suspend fun runSync(
    active: ActiveSession,
    station: SavedStation,
    onState: (SyncState) -> Unit,
) {
    onState(SyncState.Running)
    try {
        val now = nowMs()
        active.setTime(now)
        val from = station.lastSyncMs ?: (now - DEFAULT_LOOKBACK_MS)
        // Paginate so a single request never blows past the chunked-notify
        // throughput budget. Each row is ~70 B CBOR; 500 rows fit in ~6 s
        // of notify traffic, well under our 15 s timeout.
        val readings = active.requestRawReadings(
            fromMs = from, toMs = now, limit = SYNC_LIMIT,
        )
        // Advance the cursor to the timestamp of the last reading we got, so
        // a follow-up sync picks up exactly where this one left off (avoids
        // re-downloading the same window if the server had more than the limit).
        val nextSyncFrom = readings.lastOrNull()?.tsMs?.plus(1) ?: now
        StationsRepository.updateLastSync(station.bleId, nextSyncFrom)
        val more = readings.size >= SYNC_LIMIT
        onState(SyncState.Done(count = readings.size, atMs = now, more = more))
    } catch (e: BleError) {
        onState(SyncState.Failed(e.message ?: e::class.simpleName ?: "sync failed"))
    } catch (e: Throwable) {
        onState(SyncState.Failed(e.message ?: e::class.simpleName ?: "unexpected error"))
    }
}

@Composable
private fun SyncStatusBanner(state: SyncState) {
    when (state) {
        SyncState.Idle -> Unit
        SyncState.Running -> Text(
            text = "Enviando hora y solicitando lecturas...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is SyncState.Done -> Text(
            text = if (state.more)
                "Listo: ${state.count} lecturas descargadas. " +
                "Hay más pendientes — pulsa de nuevo para continuar."
            else
                "Listo: ${state.count} lecturas descargadas (${formatRelativeMs(state.atMs)}).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        is SyncState.Failed -> Text(
            text = "Error de sincronización: ${state.message}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun DeviceStatusCard(station: SavedStation, status: StatusMsg) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = station.displayName,
                    style = MaterialTheme.typography.titleLarge,
                )
                ConnectionStatusChip(connected = true)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = station.bleId,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            InfoRow(label = "Firmware", value = "savia ${status.fw}")
            Spacer(Modifier.height(8.dp))
            InfoRow(label = "Uptime", value = formatUptime(status.uptimeS))
            Spacer(Modifier.height(8.dp))
            InfoRow(
                label = "Último sync",
                value = station.lastSyncMs?.let { formatRelativeMs(it) } ?: "Sin sync",
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            modifier = Modifier.weight(0.4f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            modifier = Modifier.weight(0.6f),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun formatUptime(seconds: Long): String {
    val days = seconds / 86_400
    val hours = (seconds % 86_400) / 3_600
    val mins = (seconds % 3_600) / 60
    return buildString {
        if (days > 0) append("${days}d ")
        if (hours > 0 || days > 0) append("${hours}h ")
        append("${mins}m")
    }.trim()
}

private fun formatRelativeMs(ms: Long): String {
    val delta = nowMs() - ms
    val minutes = delta / 60_000
    return when {
        delta < 30_000 -> "hace instantes"
        minutes < 1 -> "hace menos de 1 min"
        minutes < 60 -> "hace ${minutes} min"
        minutes < 1440 -> "hace ${minutes / 60} h"
        else -> "hace ${minutes / 1440} d"
    }
}
