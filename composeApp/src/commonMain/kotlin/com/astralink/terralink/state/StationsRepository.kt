package com.astralink.terralink.state

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.astralink.terralink.model.SavedStation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * The list of stations the user has paired with. Persisted in a single
 * DataStore preference entry serialized as a JSON array.
 *
 * Stays a singleton object so call sites (StationsListScreen, ScanScreen,
 * App.kt) keep their non-suspend API. The actual persistence happens off
 * a background scope; the in-memory StateFlow is the authoritative
 * source for the UI and is kept in sync by collecting the DataStore Flow.
 *
 * `init(store)` must be invoked once at app launch with the platform
 * DataStore (see StationsDataStore expect/actual). Calling add/remove/
 * find/updateLastSync before init throws on first persistence write.
 */
object StationsRepository {

    private val _stations = MutableStateFlow<List<SavedStation>>(emptyList())
    val stations: StateFlow<List<SavedStation>> = _stations.asStateFlow()

    private lateinit var store: DataStore<Preferences>
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val key = stringPreferencesKey("stations_json")
    private val json = Json { ignoreUnknownKeys = true }
    private var initialized = false

    fun init(store: DataStore<Preferences>) {
        if (initialized) return
        this.store = store
        initialized = true
        // Hot-loop the preference flow into our StateFlow so the UI reflects
        // both startup-load and any subsequent edits we make.
        ioScope.launch {
            store.data.collect { prefs ->
                _stations.value = decode(prefs[key])
            }
        }
    }

    fun add(station: SavedStation) {
        requireInitialized()
        ioScope.launch {
            store.edit { prefs ->
                val current = decode(prefs[key])
                if (current.none { it.bleId == station.bleId }) {
                    prefs[key] = json.encodeToString(current + station)
                }
            }
        }
    }

    fun remove(bleId: String) {
        requireInitialized()
        ioScope.launch {
            store.edit { prefs ->
                val current = decode(prefs[key])
                prefs[key] = json.encodeToString(current.filterNot { it.bleId == bleId })
            }
        }
    }

    fun find(bleId: String): SavedStation? =
        _stations.value.firstOrNull { it.bleId == bleId }

    fun updateLastSync(bleId: String, ms: Long) = update(bleId) { it.copy(lastSyncMs = ms) }

    /** Persist the board clock snapshot read over BLE (home renders it ticking). */
    fun updateClock(bleId: String, clockMs: Long, readAtMs: Long, offsetMin: Int) = update(bleId) {
        it.copy(clockMs = clockMs, clockReadAtMs = readAtMs, clockOffsetMin = offsetMin)
    }

    /** Stamp the last on-demand LoRa ping so the UI can rate-limit uplinks. */
    fun updateLastLoraPing(bleId: String, ms: Long) = update(bleId) { it.copy(lastLoraPingMs = ms) }

    /** Remember (or forget) that the first-run wizard was dismissed on a factory station. */
    fun setSetupSkipped(bleId: String, skipped: Boolean) = update(bleId) { it.copy(setupSkipped = skipped) }

    /** Rewrite one station in place; the preference flow pushes the result to [stations]. */
    private fun update(bleId: String, transform: (SavedStation) -> SavedStation) {
        requireInitialized()
        ioScope.launch {
            store.edit { prefs ->
                val updated = decode(prefs[key]).map { if (it.bleId == bleId) transform(it) else it }
                prefs[key] = json.encodeToString(updated)
            }
        }
    }

    private fun decode(raw: String?): List<SavedStation> {
        if (raw.isNullOrEmpty()) return emptyList()
        return runCatching { json.decodeFromString<List<SavedStation>>(raw) }
            .getOrElse { emptyList() }
    }

    private fun requireInitialized() {
        check(initialized) {
            "StationsRepository not initialized. Call init(createStationsDataStore()) " +
                "from MainActivity.onCreate or MainViewController() before composing App()."
        }
    }
}
