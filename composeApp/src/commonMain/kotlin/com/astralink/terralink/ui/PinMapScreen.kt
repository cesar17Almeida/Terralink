package com.astralink.terralink.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.astralink.terralink.ble.protocol.PinmapMsg
import com.astralink.terralink.ble.session.ActiveSession
import com.astralink.terralink.ui.components.BackIconButton
import com.astralink.terralink.ui.components.PinMapCard

private sealed class PinMapPhase {
    data object Loading : PinMapPhase()
    data class Ready(val pinmap: PinmapMsg?) : PinMapPhase()
    data class Failed(val message: String) : PinMapPhase()
}

/** Full-screen view of the station's GPIO allocation. Same source of truth (the
 *  device pinmap, ...0015) the sensor wizard uses to block taken pins. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PinMapScreen(active: ActiveSession, onBack: () -> Unit) {
    var phase by remember { mutableStateOf<PinMapPhase>(PinMapPhase.Loading) }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(reloadKey) {
        phase = PinMapPhase.Loading
        phase = try {
            PinMapPhase.Ready(active.readPinmap())
        } catch (e: Throwable) {
            PinMapPhase.Failed(e.message ?: "No se pudo leer el mapa de pines")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mapa de pines", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { BackIconButton(onClick = onBack) },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
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
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                ) {
                    PinMapCard(pinmap = p.pinmap)
                }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { content() }
}
