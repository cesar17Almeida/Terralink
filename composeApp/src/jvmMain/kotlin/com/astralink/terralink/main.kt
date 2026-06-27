package com.astralink.terralink

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.astralink.terralink.state.LoraConsoleRepository
import com.astralink.terralink.state.ReadingsRepository
import com.astralink.terralink.state.StationsRepository
import com.astralink.terralink.state.createStationsDataStore
import com.astralink.terralink.state.createTerralinkDb

fun main() {
    StationsRepository.init(createStationsDataStore())
    val db = createTerralinkDb()
    ReadingsRepository.init(db)
    LoraConsoleRepository.init(db)
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Terralink",
        ) {
            App()
        }
    }
}
