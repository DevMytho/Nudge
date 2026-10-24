package com.dev.nudge.data.backup

import com.dev.nudge.data.UserSettings
import com.dev.nudge.data.session.FocusSessionEntity
import com.dev.nudge.data.session.SessionSegmentEntity
import com.dev.nudge.model.Task
import kotlinx.serialization.Serializable

/**
 * 📦 Backup envelope (spec §9): one JSON document that captures everything
 * needed to rebuild the app on another device.
 *
 * Versioned with [schemaVersion] so a future app can migrate older files.
 * Google Drive stores it in the hidden `appDataFolder` — invisible to the
 * user's files, private to this app.
 */
@Serializable
data class BackupFile(
    val schemaVersion: Int = BackupCodec.CURRENT_SCHEMA_VERSION,
    val app: String = "nudge",
    val exportedAt: Long = 0L,
    val tasks: List<Task> = emptyList(),
    val completedTasks: List<Task> = emptyList(),
    val sessions: List<BackupSession> = emptyList(),
    val settings: BackupSettings? = null
)

/** One focus session grouped with its segments for transport. */
@Serializable
data class BackupSession(
    val session: FocusSessionEntity,
    val segments: List<SessionSegmentEntity> = emptyList()
)

/**
 * ⚙️ Device-independent settings that travel with a backup.
 *
 * Deliberately EXCLUDES device/account-local fields: onboarding flag,
 * backup bookkeeping (lastBackupAt, autoBackup, driveAccountEmail) and the
 * ringtone URI (RingtoneManager URIs don't survive a device transfer).
 */
@Serializable
data class BackupSettings(
    val displayName: String? = null,
    val accentColor: String = "BLUE",
    val themeMode: String = "SYSTEM",
    val focusMinutes: Int = UserSettings.DEFAULT_FOCUS_MINUTES,
    val shortBreakMinutes: Int = UserSettings.DEFAULT_SHORT_BREAK_MINUTES,
    val longBreakMinutes: Int = UserSettings.DEFAULT_LONG_BREAK_MINUTES,
    val cyclesBeforeLongBreak: Int = UserSettings.DEFAULT_CYCLES_BEFORE_LONG_BREAK,
    val autoStartNextPhase: Boolean = false,
    val dailyGoalMinutes: Int = UserSettings.DEFAULT_DAILY_GOAL_MINUTES,
    val streakMinMinutes: Int = UserSettings.DEFAULT_STREAK_MIN_MINUTES,
    val weekStartsOn: String = "MON",
    val hapticsEnabled: Boolean = true
)

fun UserSettings.toBackupSettings() = BackupSettings(
    displayName = displayName,
    accentColor = accentColor.name,
    themeMode = themeMode.name,
    focusMinutes = focusMinutes,
    shortBreakMinutes = shortBreakMinutes,
    longBreakMinutes = longBreakMinutes,
    cyclesBeforeLongBreak = cyclesBeforeLongBreak,
    autoStartNextPhase = autoStartNextPhase,
    dailyGoalMinutes = dailyGoalMinutes,
    streakMinMinutes = streakMinMinutes,
    weekStartsOn = weekStartsOn.name,
    hapticsEnabled = hapticsEnabled
)

/**
 * Overlay restored preferences onto the current device settings, keeping
 * this device's backup/account fields intact. Unknown enum values from a
 * newer/older file fall back to the current value (tolerant import).
 */
fun BackupSettings.applyTo(current: UserSettings): UserSettings = current.copy(
    displayName = displayName,
    accentColor = com.dev.nudge.data.AccentColor
        .entries.firstOrNull { it.name == accentColor } ?: current.accentColor,
    themeMode = com.dev.nudge.data.ThemeMode
        .entries.firstOrNull { it.name == themeMode } ?: current.themeMode,
    focusMinutes = focusMinutes.coerceIn(1, 240),
    shortBreakMinutes = shortBreakMinutes.coerceIn(1, 120),
    longBreakMinutes = longBreakMinutes.coerceIn(1, 240),
    cyclesBeforeLongBreak = cyclesBeforeLongBreak.coerceIn(1, 12),
    autoStartNextPhase = autoStartNextPhase,
    dailyGoalMinutes = dailyGoalMinutes.coerceIn(5, 24 * 60),
    streakMinMinutes = streakMinMinutes.coerceIn(1, 240),
    weekStartsOn = com.dev.nudge.data.WeekStart
        .entries.firstOrNull { it.name == weekStartsOn } ?: current.weekStartsOn,
    hapticsEnabled = hapticsEnabled
)