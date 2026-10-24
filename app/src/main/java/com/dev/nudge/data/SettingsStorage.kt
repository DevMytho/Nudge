package com.dev.nudge.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

val Context.settingsDataStore by preferencesDataStore("user_settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class AccentColor { BLUE, GREEN, PURPLE, ORANGE, ROSE, MONO }

enum class WeekStart { MON, SUN }

/**
 * ⚙️ User settings (spec §3.4), persisted in DataStore.
 *
 * Everything has a safe default so the app works before onboarding exists.
 * The theme/ringtone keys already used by older versions are read/written
 * through the same store where possible; the legacy dark-mode toggle and
 * ringtone prefs keep their own DataStores and are migrated by Phase 5.
 */
data class UserSettings(
    val onboardingCompleted: Boolean = false,
    val displayName: String? = null,
    val accentColor: AccentColor = AccentColor.BLUE,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val focusMinutes: Int = DEFAULT_FOCUS_MINUTES,
    val shortBreakMinutes: Int = DEFAULT_SHORT_BREAK_MINUTES,
    val longBreakMinutes: Int = DEFAULT_LONG_BREAK_MINUTES,
    val cyclesBeforeLongBreak: Int = DEFAULT_CYCLES_BEFORE_LONG_BREAK,
    val autoStartNextPhase: Boolean = false,
    val dailyGoalMinutes: Int = DEFAULT_DAILY_GOAL_MINUTES,
    val streakMinMinutes: Int = DEFAULT_STREAK_MIN_MINUTES,
    val weekStartsOn: WeekStart = WeekStart.MON,
    val hapticsEnabled: Boolean = true,
    val lastBackupAt: Long? = null,
    val autoBackupEnabled: Boolean = false,
    val driveAccountEmail: String? = null
) {
    companion object {
        // 🔥 25 is only the DEFAULT of this setting — never a hardcoded timer.
        const val DEFAULT_FOCUS_MINUTES = 25
        const val DEFAULT_SHORT_BREAK_MINUTES = 5
        const val DEFAULT_LONG_BREAK_MINUTES = 15
        const val DEFAULT_CYCLES_BEFORE_LONG_BREAK = 4
        const val DEFAULT_DAILY_GOAL_MINUTES = 120
        const val DEFAULT_STREAK_MIN_MINUTES = 25
    }
}

object SettingsStorage {

    private object Keys {
        val ONBOARDING = booleanPreferencesKey("onboarding_completed")
        val DISPLAY_NAME = stringPreferencesKey("display_name")
        val ACCENT = stringPreferencesKey("accent_color")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val FOCUS_MIN = intPreferencesKey("focus_minutes")
        val SHORT_BREAK = intPreferencesKey("short_break_minutes")
        val LONG_BREAK = intPreferencesKey("long_break_minutes")
        val CYCLES_BEFORE_LONG = intPreferencesKey("cycles_before_long_break")
        val AUTO_START = booleanPreferencesKey("auto_start_next_phase")
        val DAILY_GOAL = intPreferencesKey("daily_goal_minutes")
        val STREAK_MIN = intPreferencesKey("streak_min_minutes")
        val WEEK_STARTS = stringPreferencesKey("week_starts_on")
        val HAPTICS = booleanPreferencesKey("haptics_enabled")
        val AUTO_BACKUP = booleanPreferencesKey("auto_backup_enabled")
        val DRIVE_EMAIL = stringPreferencesKey("drive_account_email")
    }

    // epoch millis exceeds Int, hence a Long-typed key
    private val LAST_BACKUP_AT = androidx.datastore.preferences.core.longPreferencesKey("last_backup_at")

    /**
     * 🔁 One-time migration of the pre-v3 dark-mode toggle (spec §5): an
     * explicit old choice becomes DARK/LIGHT; devices that never touched
     * the old toggle keep the new SYSTEM default. Clears the legacy store.
     */
    suspend fun migrateLegacyTheme(context: Context) {
        val p = context.settingsDataStore.data.first()
        if (p[Keys.THEME_MODE] != null) return // already migrated / chosen
        val legacy = ThemeStorage.loadLegacyOrNull(context) ?: return
        save(
            context,
            load(context).copy(themeMode = if (legacy) ThemeMode.DARK else ThemeMode.LIGHT)
        )
        ThemeStorage.clearLegacy(context)
    }

    suspend fun load(context: Context): UserSettings {
        return try {
            val p = context.settingsDataStore.data.first()
            UserSettings(
                onboardingCompleted = p[Keys.ONBOARDING] ?: false,
                displayName = p[Keys.DISPLAY_NAME],
                accentColor = p[Keys.ACCENT]?.let { runCatching { AccentColor.valueOf(it) }.getOrNull() }
                    ?: AccentColor.BLUE,
                themeMode = p[Keys.THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                    ?: ThemeMode.SYSTEM,
                focusMinutes = p[Keys.FOCUS_MIN] ?: UserSettings.DEFAULT_FOCUS_MINUTES,
                shortBreakMinutes = p[Keys.SHORT_BREAK] ?: UserSettings.DEFAULT_SHORT_BREAK_MINUTES,
                longBreakMinutes = p[Keys.LONG_BREAK] ?: UserSettings.DEFAULT_LONG_BREAK_MINUTES,
                cyclesBeforeLongBreak = p[Keys.CYCLES_BEFORE_LONG]
                    ?: UserSettings.DEFAULT_CYCLES_BEFORE_LONG_BREAK,
                autoStartNextPhase = p[Keys.AUTO_START] ?: false,
                dailyGoalMinutes = p[Keys.DAILY_GOAL] ?: UserSettings.DEFAULT_DAILY_GOAL_MINUTES,
                streakMinMinutes = p[Keys.STREAK_MIN] ?: UserSettings.DEFAULT_STREAK_MIN_MINUTES,
                weekStartsOn = p[Keys.WEEK_STARTS]?.let { runCatching { WeekStart.valueOf(it) }.getOrNull() }
                    ?: WeekStart.MON,
                hapticsEnabled = p[Keys.HAPTICS] ?: true,
                lastBackupAt = p[LAST_BACKUP_AT],
                autoBackupEnabled = p[Keys.AUTO_BACKUP] ?: false,
                driveAccountEmail = p[Keys.DRIVE_EMAIL]
            )
        } catch (e: Exception) {
            e.printStackTrace()
            UserSettings()
        }
    }

    suspend fun save(context: Context, s: UserSettings) {
        context.settingsDataStore.edit { p ->
            p[Keys.ONBOARDING] = s.onboardingCompleted
            if (s.displayName == null) p.remove(Keys.DISPLAY_NAME) else p[Keys.DISPLAY_NAME] = s.displayName
            p[Keys.ACCENT] = s.accentColor.name
            p[Keys.THEME_MODE] = s.themeMode.name
            p[Keys.FOCUS_MIN] = s.focusMinutes
            p[Keys.SHORT_BREAK] = s.shortBreakMinutes
            p[Keys.LONG_BREAK] = s.longBreakMinutes
            p[Keys.CYCLES_BEFORE_LONG] = s.cyclesBeforeLongBreak
            p[Keys.AUTO_START] = s.autoStartNextPhase
            p[Keys.DAILY_GOAL] = s.dailyGoalMinutes
            p[Keys.STREAK_MIN] = s.streakMinMinutes
            p[Keys.WEEK_STARTS] = s.weekStartsOn.name
            p[Keys.HAPTICS] = s.hapticsEnabled
            if (s.lastBackupAt == null) p.remove(LAST_BACKUP_AT) else p[LAST_BACKUP_AT] = s.lastBackupAt
            p[Keys.AUTO_BACKUP] = s.autoBackupEnabled
            if (s.driveAccountEmail == null) p.remove(Keys.DRIVE_EMAIL)
            else p[Keys.DRIVE_EMAIL] = s.driveAccountEmail
        }
    }
}
