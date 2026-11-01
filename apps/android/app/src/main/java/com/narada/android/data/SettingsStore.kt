package com.narada.android.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.narada.domain.DeviceNamePolicy
import com.narada.domain.WorkspaceSettings
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

@Singleton
class SettingsStore @Inject constructor(private val preferences: DataStore<Preferences>) {
    private object Keys {
        val name = stringPreferencesKey("device_name")
        val setup = booleanPreferencesKey("setup_completed")
        val notifications = booleanPreferencesKey("notifications_enabled")
        val reduceMotion = booleanPreferencesKey("reduce_motion")
    }
    // Intentionally no catch/emptyPreferences and no ReplaceFileCorruptionHandler.
    val settings = preferences.data.map { p ->
        WorkspaceSettings(
            p[Keys.name]?.let(DeviceNamePolicy::normalize) ?: "My phone",
            p[Keys.setup] ?: false, p[Keys.notifications] ?: false, p[Keys.reduceMotion] ?: false,
        )
    }
    suspend fun current() = settings.first()
    suspend fun completeSetup(name: String) {
        val normalized = DeviceNamePolicy.normalize(name)
        preferences.edit { it[Keys.name] = normalized; it[Keys.setup] = true }
    }
    suspend fun changeName(name: String) {
        val normalized = DeviceNamePolicy.normalize(name)
        preferences.edit { it[Keys.name] = normalized }
    }
    suspend fun notifications(enabled: Boolean) { preferences.edit { it[Keys.notifications] = enabled } }
    suspend fun reduceMotion(enabled: Boolean) { preferences.edit { it[Keys.reduceMotion] = enabled } }
}
