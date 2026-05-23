package com.astralink.terralink

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import com.astralink.terralink.model.mockDevice
import com.astralink.terralink.ui.HomeScreen
import com.astralink.terralink.ui.UpdateFirmwareScreen

private sealed interface Screen {
    data object Home : Screen
    data object UpdateFirmware : Screen
}

@Composable
@Preview
fun App() {
    MaterialTheme {
        var screen by remember { mutableStateOf<Screen>(Screen.Home) }
        when (screen) {
            Screen.Home -> HomeScreen(
                device = mockDevice,
                onUpdateFirmware = { screen = Screen.UpdateFirmware },
            )
            Screen.UpdateFirmware -> UpdateFirmwareScreen(
                device = mockDevice,
                onBack = { screen = Screen.Home },
            )
        }
    }
}
