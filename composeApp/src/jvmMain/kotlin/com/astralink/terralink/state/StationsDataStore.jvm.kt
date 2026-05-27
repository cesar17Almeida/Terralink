package com.astralink.terralink.state

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import okio.Path.Companion.toPath

actual fun createStationsDataStore(): DataStore<Preferences> {
    val home = System.getProperty("user.home") ?: "."
    val dir = File(home, ".terralink").apply { mkdirs() }
    return PreferenceDataStoreFactory.createWithPath(
        produceFile = { File(dir, "stations.preferences_pb").absolutePath.toPath() },
    )
}
