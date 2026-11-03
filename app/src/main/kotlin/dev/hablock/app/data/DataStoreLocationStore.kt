package dev.hablock.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.hablock.app.domain.model.LocationRule
import dev.hablock.app.domain.model.DwellDayState
import dev.hablock.app.domain.service.LocationStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val CONFIG_KEY = stringPreferencesKey("location_config")
private val DAY_KEY = stringPreferencesKey("location_day")

class DataStoreLocationStore(
    private val dataStore: DataStore<Preferences>,
    private val json: Json,
) : LocationStore {

    override suspend fun config(): LocationRule {
        val raw = dataStore.data.first()[CONFIG_KEY] ?: return LocationRule()
        return runCatching { json.decodeFromString(LocationRule.serializer(), raw) }.getOrDefault(LocationRule())
    }

    override suspend fun saveConfig(config: LocationRule) {
        dataStore.edit { it[CONFIG_KEY] = json.encodeToString(LocationRule.serializer(), config) }
    }

    override suspend fun day(): DwellDayState? {
        val raw = dataStore.data.first()[DAY_KEY] ?: return null
        return runCatching { json.decodeFromString(DwellDayState.serializer(), raw) }.getOrNull()
    }

    override suspend fun saveDay(state: DwellDayState) {
        dataStore.edit { it[DAY_KEY] = json.encodeToString(DwellDayState.serializer(), state) }
    }
}
