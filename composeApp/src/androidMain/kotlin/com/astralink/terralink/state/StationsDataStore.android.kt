package com.astralink.terralink.state

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.astralink.terralink.ble.AndroidBleContext
import okio.Path.Companion.toPath

actual fun createStationsDataStore(): DataStore<Preferences> {
    val ctx = AndroidBleContext.appContext
    return PreferenceDataStoreFactory.createWithPath(
        produceFile = {
            ctx.filesDir.resolve("stations.preferences_pb").absolutePath.toPath()
        },
    )
}
