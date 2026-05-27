package com.astralink.terralink

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.astralink.terralink.state.StationsRepository
import com.astralink.terralink.state.createStationsDataStore

fun main() {
    StationsRepository.init(createStationsDataStore())
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Terralink",
        ) {
            App()
        }
    }
}
