package com.astralink.terralink

import androidx.compose.ui.window.ComposeUIViewController
import com.astralink.terralink.state.LoraConsoleRepository
import com.astralink.terralink.state.ReadingsRepository
import com.astralink.terralink.state.StationsRepository
import com.astralink.terralink.state.createStationsDataStore
import com.astralink.terralink.state.createTerralinkDb

fun MainViewController() = ComposeUIViewController {
    // init is idempotent; safe to call on every view controller creation.
    StationsRepository.init(createStationsDataStore())
    val db = createTerralinkDb()
    ReadingsRepository.init(db)
    LoraConsoleRepository.init(db)
    App()
}
