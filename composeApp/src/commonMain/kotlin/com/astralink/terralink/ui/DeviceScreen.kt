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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import kotlinx.coroutines.launch
import com.astralink.terralink.model.SavedStation
import com.astralink.terralink.state.StationsRepository
import com.astralink.terralink.ui.components.BackIconButton
import com.astralink.terralink.ui.components.ConnectionStatusChip
import com.astralink.terralink.ui.components.PasswordField
import com.astralink.terralink.ui.components.PremiumTile
import com.astralink.terralink.util.nowMs

private sealed class ConnState {
    data object Connecting : ConnState()
    data class NeedsAuth(val active: ActiveSession) : ConnState()
    data class Ready(val session: ActiveSession, val status: StatusMsg) : ConnState()
    data class Failed(val message: String) : ConnState()
}

// setTime + readStatus once we're allowed (open or authenticated).
private suspend fun readyFrom(active: ActiveSession): ConnState = try {
    runCatching { active.setTime(nowMs()) }
    ConnState.Ready(active, active.readStatus())
} catch (e: Throwable) {
    ConnState.Failed(e.message ?: e::class.simpleName ?: "unexpected error")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(
    station: SavedStation,
    session: SaviaSession,
    onSyncData: (ActiveSession) -> Unit,
    onViewPredictions: (ActiveSession) -> Unit,
    onConfigure: (ActiveSession) -> Unit,
    onBack: () -> Unit,
) {
    var state by remember { mutableStateOf<ConnState>(ConnState.Connecting) }
    var retryKey by remember { mutableStateOf(0) }
    var authSubmitting by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val stations by StationsRepository.stations.collectAsStateWithLifecycle()
    val currentStation = stations.firstOrNull { it.bleId == station.bleId } ?: station

    LaunchedEffect(currentStation.bleId, retryKey) {
        state = ConnState.Connecting
        authError = null
        state = try {
            val active = session.connect(currentStation.bleId)
            // Tolerate a firmware/cache without the auth characteristic (older GATT
            // or stale iOS cache): treat as open instead of failing the connection.
            val auth = runCatching { active.readAuthState() }.getOrNull()
            // Locked station: prompt for the password before doing anything else.
            if (auth != null && auth.prov && !auth.authed) ConnState.NeedsAuth(active)
            else readyFrom(active)
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
                navigationIcon = { BackIconButton(onClick = onBack) },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (val s = state) {
                ConnState.Connecting -> ConnectingPanel(currentStation)
                is ConnState.Failed -> FailedPanel(s.message, onRetry = { retryKey++ })
                is ConnState.NeedsAuth -> AuthPanel(
                    stationName = currentStation.displayName,
                    submitting = authSubmitting,
                    error = authError,
                    onSubmit = { pw ->
                        authSubmitting = true
                        authError = null
                        scope.launch {
                            val ok = runCatching { s.active.authenticate(pw) }.getOrDefault(false)
                            if (ok) state = readyFrom(s.active)
                            else { authError = "Contraseña incorrecta"; authSubmitting = false }
                        }
                    },
                )
                is ConnState.Ready -> ReadyPanel(
                    station = currentStation,
                    session = s.session,
                    status = s.status,
                    onSyncData = { onSyncData(s.session) },
                    onViewPredictions = { onViewPredictions(s.session) },
                    onConfigure = { onConfigure(s.session) },
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
private fun AuthPanel(
    stationName: String,
    submitting: Boolean,
    error: String?,
    onSubmit: (String) -> Unit,
) {
    var pw by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("🔒 Estación protegida", style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("Introduce la contraseña de $stationName",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        PasswordField(
            value = pw,
            onValueChange = { pw = it },
            label = "Contraseña",
            isError = error != null,
            supportingText = { error?.let { Text(it, color = MaterialTheme.colorScheme.error) } },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { onSubmit(pw) },
            enabled = pw.isNotEmpty() && !submitting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (submitting) "Comprobando…" else "Desbloquear")
        }
    }
}

@Composable
private fun ReadyPanel(
    station: SavedStation,
    session: ActiveSession,
    status: StatusMsg,
    onSyncData: () -> Unit,
    onViewPredictions: () -> Unit,
    onConfigure: () -> Unit,
) {
    // How many readings the station currently holds (mostly mock data today).
    var readingCount by remember(session) { mutableStateOf<Long?>(null) }
    LaunchedEffect(session) {
        readingCount = runCatching { session.requestRawCount() }.getOrNull()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DeviceStatusCard(station = station, status = status, readingCount = readingCount)
        TileGrid(
            onSyncData = onSyncData,
            onViewPredictions = onViewPredictions,
            onConfigure = onConfigure,
        )
    }
}

@Composable
private fun TileGrid(
    onSyncData: () -> Unit,
    onViewPredictions: () -> Unit,
    onConfigure: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PremiumTile(
            title = "Sincronizar", caption = "Descargar datos", glyph = "↻",
            onClick = onSyncData, modifier = Modifier.weight(1f),
        )
        PremiumTile(
            title = "Predicciones", caption = "Salida del modelo", glyph = "📈",
            onClick = onViewPredictions, modifier = Modifier.weight(1f),
        )
        PremiumTile(
            title = "Configurar", caption = "Ajustes", glyph = "⚙",
            onClick = onConfigure, modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DeviceStatusCard(station: SavedStation, status: StatusMsg, readingCount: Long?) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = station.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                ConnectionStatusChip(connected = true)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "fw ${status.fw} · " + (readingCount?.let { "$it lecturas" } ?: "… lecturas"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Último sync " + (station.lastSyncMs?.let { formatRelativeMs(it) } ?: "—"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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
