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
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.Icon
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
import com.astralink.terralink.ble.protocol.LoraStatus
import com.astralink.terralink.ble.protocol.StatusMsg
import com.astralink.terralink.ble.session.ActiveSession
import com.astralink.terralink.ble.session.SaviaSession
import kotlinx.coroutines.launch
import com.astralink.terralink.model.SavedStation
import com.astralink.terralink.state.StationsRepository
import com.astralink.terralink.ui.components.BackIconButton
import com.astralink.terralink.ui.components.LoraPingDialog
import com.astralink.terralink.ui.components.LoraSignalGlance
import com.astralink.terralink.ui.components.PasswordField
import com.astralink.terralink.ui.components.PremiumTile
import com.astralink.terralink.ui.components.TerraIcons
import com.astralink.terralink.ui.components.dismissKeyboardOnTap
import com.astralink.terralink.util.formatRelativeMs
import com.astralink.terralink.util.nowMs
import com.astralink.terralink.util.stationClockText
import kotlinx.coroutines.delay

private sealed class ConnState {
    data object Connecting : ConnState()
    data class NeedsAuth(val active: ActiveSession) : ConnState()
    data class Ready(val session: ActiveSession, val status: StatusMsg) : ConnState()
    data class Failed(val message: String) : ConnState()
}

// setTime + readStatus once we're allowed (open or authenticated).
private suspend fun readyFrom(active: ActiveSession, bleId: String): ConnState = try {
    runCatching { active.setTime(nowMs()) }
    val status = active.readStatus()
    // Remember the board clock so the home list can render it ticking.
    status.nowMs?.let {
        StationsRepository.updateClock(bleId, it, nowMs(), status.utcOffsetMin ?: 0)
    }
    ConnState.Ready(active, status)
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
    onOpenConnectivity: (ActiveSession) -> Unit,
    onOpenSensors: (ActiveSession) -> Unit,
    onOpenPinMap: (ActiveSession) -> Unit,
    onOpenLifecycle: (ActiveSession) -> Unit,
    onOpenAccuracy: (ActiveSession) -> Unit,
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
        authSubmitting = false // clear stale flag so re-entry into NeedsAuth has an enabled button
        state = try {
            val active = session.connect(currentStation.bleId)
            // Tolerate a firmware/cache without the auth characteristic (older GATT
            // or stale iOS cache): treat as open instead of failing the connection.
            val auth = runCatching { active.readAuthState() }.getOrNull()
            // Locked station: prompt for the password before doing anything else.
            if (auth != null && auth.prov && !auth.authed) ConnState.NeedsAuth(active)
            else readyFrom(active, station.bleId)
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
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding).dismissKeyboardOnTap()) {
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
                            authSubmitting = false // reset before readyFrom, which may yield Failed
                            if (ok) state = readyFrom(s.active, station.bleId)
                            else authError = "Contraseña incorrecta"
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
                    onOpenConnectivity = { onOpenConnectivity(s.session) },
                    onOpenSensors = { onOpenSensors(s.session) },
                    onOpenPinMap = { onOpenPinMap(s.session) },
                    onOpenLifecycle = { onOpenLifecycle(s.session) },
                    onOpenAccuracy = { onOpenAccuracy(s.session) },
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
        Icon(
            imageVector = TerraIcons.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text("Estación protegida", style = MaterialTheme.typography.titleMedium,
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
    onOpenConnectivity: () -> Unit,
    onOpenSensors: () -> Unit,
    onOpenPinMap: () -> Unit,
    onOpenLifecycle: () -> Unit,
    onOpenAccuracy: () -> Unit,
) {
    // How many readings the station currently holds (mostly mock data today).
    var readingCount by remember(session) { mutableStateOf<Long?>(null) }
    LaunchedEffect(session) {
        readingCount = runCatching { session.requestRawCount() }.getOrNull()
    }

    // Last LoRa signal shown on the glance; the ping modal refreshes it in place.
    var loraOverride by remember(session) { mutableStateOf<LoraStatus?>(null) }
    var showLoraPing by remember { mutableStateOf(false) }
    val effLora = loraOverride ?: status.lora

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DeviceStatusCard(
            station = station,
            status = status,
            readingCount = readingCount,
            lora = effLora,
            onLoraClick = if (status.lora != null) ({ showLoraPing = true }) else null,
        )
        TileGrid(
            onSyncData = onSyncData,
            onViewPredictions = onViewPredictions,
            onConfigure = onConfigure,
            onOpenConnectivity = onOpenConnectivity,
            onOpenSensors = onOpenSensors,
            onOpenPinMap = onOpenPinMap,
            onOpenLifecycle = onOpenLifecycle,
            onOpenAccuracy = onOpenAccuracy,
        )
    }

    if (showLoraPing) {
        LoraPingDialog(
            session = session,
            bleId = station.bleId,
            initial = effLora,
            onDismiss = { showLoraPing = false },
            onResult = { loraOverride = it },
        )
    }
}

@Composable
private fun TileGrid(
    onSyncData: () -> Unit,
    onViewPredictions: () -> Unit,
    onConfigure: () -> Unit,
    onOpenConnectivity: () -> Unit,
    onOpenSensors: () -> Unit,
    onOpenPinMap: () -> Unit,
    onOpenLifecycle: () -> Unit,
    onOpenAccuracy: () -> Unit,
) {
    // Two tiles per row: wider cards so titles/captions no longer truncate.
    // An odd count leaves one empty half-slot on the last row.
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PremiumTile(
                title = "Sincronizar", caption = "Descargar datos", icon = TerraIcons.Sync,
                onClick = onSyncData, modifier = Modifier.weight(1f),
            )
            PremiumTile(
                title = "Predicciones", caption = "Salida del modelo", icon = TerraIcons.ShowChart,
                onClick = onViewPredictions, modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PremiumTile(
                title = "Periféricos", caption = "Sensores y actuadores", icon = TerraIcons.Sensors,
                onClick = onOpenSensors, modifier = Modifier.weight(1f),
            )
            PremiumTile(
                title = "Configurar", caption = "Ajustes", icon = TerraIcons.Settings,
                onClick = onConfigure, modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PremiumTile(
                title = "Conectividad", caption = "LoRa y periféricos", icon = TerraIcons.Antenna,
                onClick = onOpenConnectivity, modifier = Modifier.weight(1f),
            )
            PremiumTile(
                title = "Mapa de pines", caption = "GPIO de la estación", icon = TerraIcons.Memory,
                onClick = onOpenPinMap, modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PremiumTile(
                title = "Ciclo de vida", caption = "Pasado y futuro", icon = TerraIcons.Timeline,
                onClick = onOpenLifecycle, modifier = Modifier.weight(1f),
            )
            PremiumTile(
                title = "Predicción vs real", caption = "Acierto del LSTM", icon = TerraIcons.Target,
                onClick = onOpenAccuracy, modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DeviceStatusCard(
    station: SavedStation,
    status: StatusMsg,
    readingCount: Long?,
    lora: LoraStatus?,
    onLoraClick: (() -> Unit)?,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = station.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
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
                // Board clock, ticking each second off the last BLE status read.
                var clockTick by remember { mutableStateOf(nowMs()) }
                LaunchedEffect(Unit) {
                    while (true) { delay(1_000); clockTick = nowMs() }
                }
                stationClockText(station, clockTick)?.let { clock ->
                    Text(
                        text = clock,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            LoraSignalGlance(lora = lora, onClick = onLoraClick)
        }
    }
}
