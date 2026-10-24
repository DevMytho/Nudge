package com.dev.nudge.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

val Context.themeDataStore by preferencesDataStore("theme")

/**
 * 🏚️ LEGACY dark-mode toggle store (pre-v3). Phase 5 migrates it into
 * SettingsStorage's ThemeMode and then clears it — see
 * [SettingsStorage.migrateLegacyTheme]. Kept only for that migration.
 */
object ThemeStorage {

    private val DARK_MODE_KEY = booleanPreferencesKey("dark_mode")

    /** null = the user never touched the old toggle (nothing to migrate). */
    suspend fun loadLegacyOrNull(context: Context): Boolean? {
        val prefs = context.themeDataStore.data.first()
        return prefs[DARK_MODE_KEY]
    }

    suspend fun clearLegacy(context: Context) {
        context.themeDataStore.edit { it.remove(DARK_MODE_KEY) }
    }
}