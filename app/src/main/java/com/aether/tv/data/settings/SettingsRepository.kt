package com.aether.tv.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.aether.tv.core.model.LauncherSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

val AetherJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true; coerceInputValues = true }

/**
 * Stores [LauncherSettings] per profile as JSON inside Preferences DataStore. Unknown/old fields are tolerated,
 * so settings survive upgrades. A corrupt entry falls back to defaults instead of crashing the launcher.
 */
@Singleton
class SettingsRepository @Inject constructor(private val store: DataStore<Preferences>) {

    private fun key(profileId: String) = stringPreferencesKey("settings_$profileId")

    fun observe(profileId: String): Flow<LauncherSettings> = store.data
        .map { decode(it[key(profileId)]) }
        .distinctUntilChanged()

    suspend fun get(profileId: String): LauncherSettings = decode(store.data.first()[key(profileId)])

    suspend fun update(profileId: String, transform: (LauncherSettings) -> LauncherSettings) {
        store.edit { prefs ->
            val updated = transform(decode(prefs[key(profileId)]))
            prefs[key(profileId)] = AetherJson.encodeToString(LauncherSettings.serializer(), updated)
        }
    }

    suspend fun replace(profileId: String, settings: LauncherSettings) = update(profileId) { settings }

    suspend fun remove(profileId: String) { store.edit { it.remove(key(profileId)) } }

    suspend fun flag(name: String): Boolean = store.data.first()[booleanPreferencesKey("flag_$name")] ?: false
    suspend fun setFlag(name: String, value: Boolean) { store.edit { it[booleanPreferencesKey("flag_$name")] = value } }
    fun observeFlag(name: String): Flow<Boolean> = store.data.map { it[booleanPreferencesKey("flag_$name")] ?: false }.distinctUntilChanged()

    companion object {
        fun decode(json: String?): LauncherSettings =
            if (json.isNullOrBlank()) LauncherSettings()
            else runCatching { AetherJson.decodeFromString(LauncherSettings.serializer(), json) }.getOrElse { LauncherSettings() }
    }
}
