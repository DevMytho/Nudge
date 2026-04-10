package com.dev.nudge.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

val Context.themeDataStore by preferencesDataStore("theme")

object ThemeStorage {

    private val DARK_MODE_KEY = booleanPreferencesKey("dark_mode")

    suspend fun saveTheme(context: Context, isDark: Boolean) {
        context.themeDataStore.edit {
            it[DARK_MODE_KEY] = isDark
        }
    }

    suspend fun loadTheme(context: Context): Boolean {
        val prefs = context.themeDataStore.data.first()
        return prefs[DARK_MODE_KEY] ?: false
    }
}