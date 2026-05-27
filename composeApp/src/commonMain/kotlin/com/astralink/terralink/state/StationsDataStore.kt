package com.astralink.terralink.state

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences

/**
 * Platform-specific factory for the DataStore that backs StationsRepository.
 * Each actual chooses an appropriate file location:
 *   - Android: filesDir
 *   - iOS:     Documents directory
 *   - JVM:     ~/.terralink/
 *
 * Constructed once at app launch; the returned DataStore is process-singleton.
 */
expect fun createStationsDataStore(): DataStore<Preferences>
