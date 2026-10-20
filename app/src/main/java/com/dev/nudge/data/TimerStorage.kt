package com.dev.nudge.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

val Context.timerDataStore by preferencesDataStore("pomodoro_timer")

enum class TimerStatus { IDLE, RUNNING, PAUSED }

/**
 * 🔥 Persistent Pomodoro timer state.
 *
 * The timer uses an end-timestamp model so it survives backgrounding,
 * rotation and process death:
 *  - RUNNING  → remaining time is derived from [endAtMillis] (wall clock —
 *               the same clock AlarmManager RTC_WAKEUP alarms use, so the
 *               UI and the alarm always agree).
 *  - PAUSED   → [remainingMillis] holds the frozen value.
 *  - IDLE     → reset to the default 25 minutes.
 */
data class TimerState(
    val status: TimerStatus = TimerStatus.IDLE,
    val endAtMillis: Long = 0L,
    val remainingMillis: Long = DEFAULT_TOTAL_MILLIS,
    val totalMillis: Long = DEFAULT_TOTAL_MILLIS,
    val focusedTaskId: String = ""
) {
    /** Remaining time at [nowMillis]; clamps at 0 when the end time has passed. */
    fun remainingAt(nowMillis: Long): Long = when (status) {
        TimerStatus.RUNNING -> (endAtMillis - nowMillis).coerceAtLeast(0L)
        else -> remainingMillis.coerceAtLeast(0L)
    }

    companion object {
        const val DEFAULT_TOTAL_MILLIS = 25 * 60 * 1000L
    }
}

object TimerStorage {

    private val STATUS_KEY = stringPreferencesKey("timer_status")
    private val END_AT_KEY = longPreferencesKey("timer_end_at")
    private val REMAINING_KEY = longPreferencesKey("timer_remaining")
    private val TOTAL_KEY = longPreferencesKey("timer_total")
    private val TASK_ID_KEY = stringPreferencesKey("timer_task_id")

    suspend fun saveState(context: Context, state: TimerState) {
        context.timerDataStore.edit { prefs ->
            prefs[STATUS_KEY] = state.status.name
            prefs[END_AT_KEY] = state.endAtMillis
            prefs[REMAINING_KEY] = state.remainingMillis
            prefs[TOTAL_KEY] = state.totalMillis
            prefs[TASK_ID_KEY] = state.focusedTaskId
        }
    }

    suspend fun loadState(context: Context): TimerState {
        return try {
            val prefs = context.timerDataStore.data.first()
            val status = prefs[STATUS_KEY]
                ?.let { name -> TimerStatus.entries.firstOrNull { it.name == name } }
                ?: TimerStatus.IDLE

            TimerState(
                status = status,
                endAtMillis = prefs[END_AT_KEY] ?: 0L,
                remainingMillis = prefs[REMAINING_KEY] ?: TimerState.DEFAULT_TOTAL_MILLIS,
                totalMillis = prefs[TOTAL_KEY] ?: TimerState.DEFAULT_TOTAL_MILLIS,
                focusedTaskId = prefs[TASK_ID_KEY] ?: ""
            )
        } catch (e: Exception) {
            e.printStackTrace()
            TimerState()
        }
    }

    suspend fun clear(context: Context) {
        context.timerDataStore.edit { it.clear() }
    }
}
