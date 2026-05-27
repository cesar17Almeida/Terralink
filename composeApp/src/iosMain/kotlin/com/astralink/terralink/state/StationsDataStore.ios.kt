package com.astralink.terralink.state

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.cinterop.ExperimentalForeignApi
import okio.Path.Companion.toPath
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

@OptIn(ExperimentalForeignApi::class)
actual fun createStationsDataStore(): DataStore<Preferences> {
    val fileManager = NSFileManager.defaultManager
    val documents = fileManager.URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = true,
        error = null,
    )
    val basePath = documents?.path ?: NSFileManager.defaultManager.currentDirectoryPath
    val fullPath = "$basePath/stations.preferences_pb"
    return PreferenceDataStoreFactory.createWithPath(
        produceFile = { fullPath.toPath() },
    )
}
