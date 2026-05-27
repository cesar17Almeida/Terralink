package com.astralink.terralink.state

import com.astralink.terralink.model.SavedStation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory list of stations the user has paired with. Singleton so
 * every screen sees the same list. State is wiped on app restart;
 * migrate to DataStore (kotlinx-serialization) when we want persistence.
 */
object StationsRepository {

    private val _stations = MutableStateFlow<List<SavedStation>>(emptyList())
    val stations: StateFlow<List<SavedStation>> = _stations.asStateFlow()

    /** Add a station, or keep the existing entry if `bleId` matches. */
    fun add(station: SavedStation) {
        _stations.update { current ->
            if (current.any { it.bleId == station.bleId }) current
            else current + station
        }
    }

    fun remove(bleId: String) {
        _stations.update { current -> current.filterNot { it.bleId == bleId } }
    }

    fun find(bleId: String): SavedStation? =
        _stations.value.firstOrNull { it.bleId == bleId }

    /** Set lastSyncMs on the matching station; no-op if not registered. */
    fun updateLastSync(bleId: String, ms: Long) {
        _stations.update { current ->
            current.map { if (it.bleId == bleId) it.copy(lastSyncMs = ms) else it }
        }
    }
}
