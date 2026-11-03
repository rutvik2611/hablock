package dev.hablock.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.hablock.app.domain.service.OverrideStore
import java.time.Instant
import kotlinx.coroutines.flow.first

private val UNTIL_KEY = stringPreferencesKey("override_until_millis")
private val LOG_KEY = stringPreferencesKey("override_log")
private const val LOG_LIMIT = 50

class DataStoreOverrideStore(private val dataStore: DataStore<Preferences>) : OverrideStore {

    override suspend fun until(): Instant? {
        val raw = dataStore.data.first()[UNTIL_KEY] ?: return null
        return raw.toLongOrNull()?.let(Instant::ofEpochMilli)
    }

    override suspend fun set(until: Instant) {
        dataStore.edit { it[UNTIL_KEY] = until.toEpochMilli().toString() }
    }

    override suspend fun clear() {
        dataStore.edit { it.remove(UNTIL_KEY) }
    }

    override suspend fun record(at: Instant, minutes: Int) {
        dataStore.edit { prefs ->
            val existing = prefs[LOG_KEY].orEmpty().split('\n').filter { it.isNotBlank() }
            val updated = (listOf("${at.toEpochMilli()}:$minutes") + existing).take(LOG_LIMIT)
            prefs[LOG_KEY] = updated.joinToString("\n")
        }
    }

    override suspend fun history(): List<String> =
        dataStore.data.first()[LOG_KEY].orEmpty().split('\n').filter { it.isNotBlank() }
}
