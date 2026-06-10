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
import com.astralink.terralink.ble.protocol.InferenceDoneMsg
import com.astralink.terralink.ble.protocol.StatusMsg
import com.astralink.terralink.ble.session.ActiveSession
import com.astralink.terralink.ble.session.SaviaSession
import kotlinx.coroutines.launch
import com.astralink.terralink.model.SavedStation
import com.astralink.terralink.state.StationsRepository
import com.astralink.terralink.ui.components.BackIconButton
import com.astralink.terralink.ui.components.ConnectionStatusChip
import com.astralink.terralink.ui.components.PremiumTile
import com.astralink.terralink.util.nowMs

private sealed class ConnState {
    data object Connecting : ConnState()
    data class Ready(val session: ActiveSession, val status: StatusMsg) : ConnState()
    data class Failed(val message: String) : ConnState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(
    station: SavedStation,
    session: SaviaSession,
    onUpdateFirmware: (ActiveSession) -> Unit,
    onSyncData: (ActiveSession) -> Unit,
    onViewPredictions: (ActiveSession) -> Unit,
    onBack: () -> Unit,
) {
    var state by remember { mutableStateOf<ConnState>(ConnState.Connecting) }
    var retryKey by remember { mutableStateOf(0) }

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
                is ConnState.Failed -> FailedPanel(s.message, onRetry = { retryKey++ })
                is ConnState.Ready -> ReadyPanel(
                    station = currentStation,
                    session = s.session,
                    status = s.status,
                    onUpdateFirmware = { onUpdateFirmware(s.session) },
                    onSyncData = { onSyncData(s.session) },
                    onViewPredictions = { onViewPredictions(s.session) },
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

private sealed class InferUiState {
    data object Idle : InferUiState()
    data object Running : InferUiState()
    data class Done(val result: InferenceDoneMsg) : InferUiState()
    data class Failed(val message: String) : InferUiState()
}

@Composable
private fun ReadyPanel(
    station: SavedStation,
    session: ActiveSession,
    status: StatusMsg,
    onUpdateFirmware: () -> Unit,
    onSyncData: () -> Unit,
    onViewPredictions: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    // How many readings the station currently holds (mostly mock data today).
    var readingCount by remember(session) { mutableStateOf<Long?>(null) }
    LaunchedEffect(session) {
        readingCount = runCatching { session.requestRawCount() }.getOrNull()
    }

    var infer by remember(session) { mutableStateOf<InferUiState>(InferUiState.Idle) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DeviceStatusCard(station = station, status = status, readingCount = readingCount)
        TileGrid(
            onUpdateFirmware = onUpdateFirmware,
            onSyncData = onSyncData,
            inferring = infer is InferUiState.Running,
            onForceInference = {
                infer = InferUiState.Running
                scope.launch {
                    infer = try {
                        InferUiState.Done(session.requestInference())
                    } catch (e: Throwable) {
                        InferUiState.Failed(e.message ?: "no se pudo forzar la inferencia")
                    }
                }
            },
            onViewPredictions = onViewPredictions,
        )
    }

    when (val i = infer) {
        is InferUiState.Done -> InferResultDialog(
            result = i.result,
            onViewPredictions = {
                infer = InferUiState.Idle
                onViewPredictions()
            },
            onDismiss = { infer = InferUiState.Idle },
        )
        is InferUiState.Failed -> AlertDialog(
            onDismissRequest = { infer = InferUiState.Idle },
            confirmButton = { TextButton(onClick = { infer = InferUiState.Idle }) { Text("Cerrar") } },
            title = { Text("Inferencia fallida") },
            text = { Text(i.message) },
        )
        else -> {}
    }
}

@Composable
private fun InferResultDialog(
    result: InferenceDoneMsg,
    onViewPredictions: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!result.ok) {
        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
            title = { Text("Inferencia no disponible") },
            text = { Text(result.msg ?: "La estación no pudo completar la inferencia.") },
        )
        return
    }
    val recommendation = if (result.rec == 1) "Regar mañana" else "No regar"
    val hs30 = result.hs30Min?.let { "HS30 mínimo previsto: ${(it * 100).toInt() / 100.0}" } ?: ""
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onViewPredictions) { Text("Ver predicciones") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
        title = { Text("Recomendación: $recommendation") },
        text = { Text(hs30) },
    )
}

@Composable
private fun TileGrid(
    onUpdateFirmware: () -> Unit,
    onSyncData: () -> Unit,
    inferring: Boolean,
    onForceInference: () -> Unit,
    onViewPredictions: () -> Unit,
) {
    // 2-column manual grid -- LazyVerticalGrid would be overkill for 4 items
    // and we want both rows always visible inside the parent scroll.
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PremiumTile(
                title = "Actualizar firmware",
                caption = "Subir un .bin nuevo por BLE",
                glyph = "⬆",
                onClick = onUpdateFirmware,
                modifier = Modifier.weight(1f),
            )
            PremiumTile(
                title = "Sincronizar datos",
                caption = "Descargar lecturas del sensor",
                glyph = "↻",
                onClick = onSyncData,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PremiumTile(
                title = "Forzar inferencia",
                caption = if (inferring) "Ejecutando en la estación…" else "Ejecutar el modelo ahora",
                glyph = "⚡",
                onClick = onForceInference,
                enabled = !inferring,
                modifier = Modifier.weight(1f),
            )
            PremiumTile(
                title = "Predicciones",
                caption = "Ver la salida del modelo",
                glyph = "📈",
                onClick = onViewPredictions,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DeviceStatusCard(station: SavedStation, status: StatusMsg, readingCount: Long?) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
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
                    fontWeight = FontWeight.SemiBold,
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
                label = "Lecturas en la estación",
                value = readingCount?.let { "$it" } ?: "…",
            )
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
