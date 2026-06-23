package com.astralink.terralink

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import com.astralink.terralink.ble.session.ActiveSession
import com.astralink.terralink.ble.session.SaviaSession
import com.astralink.terralink.model.SavedStation
import com.astralink.terralink.state.StationsRepository
import com.astralink.terralink.ui.ConfigurationScreen
import com.astralink.terralink.ui.DeviceScreen
import com.astralink.terralink.ui.LogsScreen
import com.astralink.terralink.ui.PredictionsScreen
import com.astralink.terralink.ui.ScanScreen
import com.astralink.terralink.ui.SplashScreen
import com.astralink.terralink.ui.StationsListScreen
import com.astralink.terralink.ui.SyncScreen
import com.astralink.terralink.ui.UpdateFirmwareScreen
import com.astralink.terralink.ui.theme.TerraTheme
import com.astralink.terralink.util.nowMs

private sealed interface Screen {
    data object Splash : Screen
    data object StationsList : Screen
    data object Scan : Screen
    data class Device(val station: SavedStation) : Screen
    data class UpdateFirmware(val station: SavedStation) : Screen
    data class Sync(val station: SavedStation) : Screen
    data class Predictions(val station: SavedStation) : Screen
    data class Configuration(val station: SavedStation) : Screen
    data class Logs(val station: SavedStation) : Screen
}

@Composable
@Preview
fun App() {
    TerraTheme {
        val session = remember { SaviaSession() }
        var screen by remember { mutableStateOf<Screen>(Screen.Splash) }
        // Shared across Device / UpdateFirmware / Sync so the L2CAP transfer
        // and the readings stream reuse the same connection rather than
        // reconnecting on every navigation.
        var activeSession by remember { mutableStateOf<ActiveSession?>(null) }

        when (val current = screen) {
            Screen.Splash -> SplashScreen(
                onTimeout = { screen = Screen.StationsList },
            )

            Screen.StationsList -> StationsListScreen(
                session = session,
                onAddStation = { screen = Screen.Scan },
                onOpenStation = { station ->
                    activeSession = null
                    screen = Screen.Device(station)
                },
            )

            Screen.Scan -> ScanScreen(
                session = session,
                onBack = { screen = Screen.StationsList },
                onPair = { device ->
                    val station = SavedStation(
                        bleId = device.id,
                        displayName = device.name ?: "Estación ${device.id.takeLast(5)}",
                        pairedAtMs = nowMs(),
                    )
                    StationsRepository.add(station)
                    activeSession = null
                    screen = Screen.Device(station)
                },
            )

            is Screen.Device -> DeviceScreen(
                station = current.station,
                session = session,
                onSyncData = { active ->
                    activeSession = active
                    screen = Screen.Sync(current.station)
                },
                onViewPredictions = { active ->
                    activeSession = active
                    screen = Screen.Predictions(current.station)
                },
                onConfigure = { active ->
                    activeSession = active
                    screen = Screen.Configuration(current.station)
                },
                onBack = {
                    activeSession = null
                    screen = Screen.StationsList
                },
            )

            is Screen.UpdateFirmware -> {
                val active = activeSession
                if (active == null) {
                    // No live session somehow (e.g. process restored). Bounce
                    // back to Device, which will reconnect.
                    screen = Screen.Device(current.station)
                } else {
                    UpdateFirmwareScreen(
                        station = current.station,
                        active = active,
                        onBack = { screen = Screen.Device(current.station) },
                    )
                }
            }

            is Screen.Sync -> {
                val active = activeSession
                if (active == null) {
                    screen = Screen.Device(current.station)
                } else {
                    SyncScreen(
                        station = current.station,
                        active = active,
                        onBack = { screen = Screen.Device(current.station) },
                    )
                }
            }

            is Screen.Predictions -> {
                val active = activeSession
                if (active == null) {
                    screen = Screen.Device(current.station)
                } else {
                    PredictionsScreen(
                        station = current.station,
                        active = active,
                        onBack = { screen = Screen.Device(current.station) },
                    )
                }
            }

            is Screen.Configuration -> {
                val active = activeSession
                if (active == null) {
                    screen = Screen.Device(current.station)
                } else {
                    ConfigurationScreen(
                        station = current.station,
                        active = active,
                        onViewLogs = { screen = Screen.Logs(current.station) },
                        onBack = { screen = Screen.Device(current.station) },
                    )
                }
            }

            is Screen.Logs -> {
                val active = activeSession
                if (active == null) {
                    screen = Screen.Device(current.station)
                } else {
                    LogsScreen(
                        station = current.station,
                        active = active,
                        onBack = { screen = Screen.Configuration(current.station) },
                    )
                }
            }
        }
    }
}
