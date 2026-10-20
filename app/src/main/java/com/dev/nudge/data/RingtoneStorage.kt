package com.dev.nudge.data

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

val Context.ringtoneDataStore by preferencesDataStore("ringtone_prefs")

/**
 * 🔊 Persists the user's chosen timer ringtone (and the critical-reminder
 * ring toggle), following the same DataStore pattern as ThemeStorage.
 */
object RingtoneStorage {

    private val RINGTONE_URI_KEY = stringPreferencesKey("timer_ringtone_uri")
    private val CRITICAL_RING_KEY = booleanPreferencesKey("critical_ring_enabled")

    suspend fun saveRingtoneUri(context: Context, uri: Uri?) {
        context.ringtoneDataStore.edit { prefs ->
            if (uri == null) prefs.remove(RINGTONE_URI_KEY)
            else prefs[RINGTONE_URI_KEY] = uri.toString()
        }
    }

    suspend fun loadRingtoneUriString(context: Context): String? {
        val prefs = context.ringtoneDataStore.data.first()
        return prefs[RINGTONE_URI_KEY]
    }

    /**
     * Best usable URI: the saved one if it still resolves, otherwise the
     * system default alarm sound.
     */
    suspend fun resolveRingtoneUri(context: Context): Uri {
        val saved = loadRingtoneUriString(context)?.let(Uri::parse)
        if (saved != null && RingtoneManager.getRingtone(context, saved) != null) {
            return saved
        }
        return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI
    }

    suspend fun loadCriticalRing(context: Context): Boolean {
        val prefs = context.ringtoneDataStore.data.first()
        return prefs[CRITICAL_RING_KEY] ?: false
    }

    suspend fun saveCriticalRing(context: Context, enabled: Boolean) {
        context.ringtoneDataStore.edit { it[CRITICAL_RING_KEY] = enabled }
    }
}
