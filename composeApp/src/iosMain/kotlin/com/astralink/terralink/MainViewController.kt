package com.astralink.terralink

import androidx.compose.ui.window.ComposeUIViewController
import com.astralink.terralink.state.StationsRepository
import com.astralink.terralink.state.createStationsDataStore

fun MainViewController() = ComposeUIViewController {
    // init is idempotent; safe to call on every view controller creation.
    StationsRepository.init(createStationsDataStore())
    App()
}
