package com.dev.nudge.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.dev.nudge.data.session.SessionMode
import com.dev.nudge.data.session.SegmentType
import kotlinx.coroutines.flow.first

val Context.timerDataStore by preferencesDataStore("pomodoro_timer")

enum class TimerStatus { IDLE, RUNNING, PAUSED }

/**
 * 🔥 Persistent state of the shared TimerEngine (Focus + Pomodoro).
 *
 * End-timestamp model: while RUNNING the remaining time is always derived
 * from [endAtEpochMs], so the UI is correct after backgrounding, rotation
 * or process death. Actual segment time is derived from the running slices
 * ([accumulatedRunMs] + now − [lastResumedAt]) — pauses are never counted.
 *
 * Persisted on every transition so BootReceiver and a cold-started
 * TimerAlarmReceiver can reconcile exactly what happened.
 */
data class TimerState(
    val sessionId: String = "",
    val mode: SessionMode? = null,          // null when idle
    val taskId: String? = null,
    val taskTitleSnapshot: String = "",
    val phase: SegmentType? = null,         // current segment type; null when idle
    val status: TimerStatus = TimerStatus.IDLE,
    /** index of the current segment within the session */
    val segmentIndex: Int = 0,
    val segmentStartedAt: Long = 0L,
    /** initial planned length of the current segment; 0 = open-ended */
    val plannedSecCurrentSegment: Int = 0,
    /** net ±5 adjustments of the current segment */
    val extensionSecCurrentSegment: Int = 0,
    /** valid while RUNNING */
    val endAtEpochMs: Long = 0L,
    /** valid while PAUSED */
    val remainingMsWhenPaused: Long = 0L,
    /** running time of the current segment, pauses excluded */
    val accumulatedRunMs: Long = 0L,
    /** when the current run slice started */
    val lastResumedAt: Long = 0L,
    /** 1-based focus block number (Pomodoro) */
    val cycleIndex: Int = 1,
    val targetCycles: Int = 4,
    /** remaining focus time to resume after a manual break; see TimerEngine */
    val carryRemainingMs: Long = 0L,
    /** finished segments (ms) — for quick summaries */
    val totalFocusMs: Long = 0L,
    val totalBreakMs: Long = 0L,
    val extensionsFocusMs: Long = 0L
) {
    fun isActive(): Boolean =
        status != TimerStatus.IDLE && phase != null && sessionId.isNotBlank()

    /** Remaining time of the current segment at [nowMillis]. */
    fun remainingAt(nowMillis: Long): Long = when (status) {
        TimerStatus.RUNNING -> (endAtEpochMs - nowMillis).coerceAtLeast(0L)
        TimerStatus.PAUSED -> remainingMsWhenPaused.coerceAtLeast(0L)
        TimerStatus.IDLE -> 0L
    }

    /** Total running time of the current segment at [nowMillis] (pauses excluded). */
    fun actualRunMsAt(nowMillis: Long): Long =
        accumulatedRunMs + if (status == TimerStatus.RUNNING) {
            (nowMillis - lastResumedAt).coerceAtLeast(0L)
        } else 0L

    /** Counting-up value for open-ended mode (plannedSec == 0). */
    fun elapsedMsAt(nowMillis: Long): Long {
        val base = totalFocusMs + totalBreakMs
        return base + actualRunMsAt(nowMillis)
    }
}

object TimerStorage {

    private val SESSION_ID = stringPreferencesKey("session_id")
    private val MODE = stringPreferencesKey("mode")
    private val TASK_ID = stringPreferencesKey("task_id")
    private val TASK_TITLE = stringPreferencesKey("task_title")
    private val PHASE = stringPreferencesKey("phase")
    private val STATUS = stringPreferencesKey("status")
    private val SEGMENT_INDEX = intPreferencesKey("segment_index")
    private val SEGMENT_STARTED_AT = longPreferencesKey("segment_started_at")
    private val PLANNED_SEC = intPreferencesKey("planned_sec")
    private val EXTENSION_SEC = intPreferencesKey("extension_sec")
    private val END_AT = longPreferencesKey("end_at")
    private val REMAINING_PAUSED = longPreferencesKey("remaining_paused")
    private val ACCUM_RUN = longPreferencesKey("accum_run")
    private val LAST_RESUMED = longPreferencesKey("last_resumed")
    private val CYCLE_INDEX = intPreferencesKey("cycle_index")
    private val TARGET_CYCLES = intPreferencesKey("target_cycles")
    private val TOTAL_FOCUS = longPreferencesKey("total_focus")
    private val TOTAL_BREAK = longPreferencesKey("total_break")
    private val EXT_FOCUS = longPreferencesKey("ext_focus")
    private val CARRY_REMAINING = longPreferencesKey("carry_remaining")

    suspend fun saveState(context: Context, state: TimerState) {
        context.timerDataStore.edit { p ->
            p[SESSION_ID] = state.sessionId
            if (state.mode == null) p.remove(MODE) else p[MODE] = state.mode.name
            if (state.taskId == null) p.remove(TASK_ID) else p[TASK_ID] = state.taskId
            p[TASK_TITLE] = state.taskTitleSnapshot
            if (state.phase == null) p.remove(PHASE) else p[PHASE] = state.phase.name
            p[STATUS] = state.status.name
            p[SEGMENT_INDEX] = state.segmentIndex
            p[SEGMENT_STARTED_AT] = state.segmentStartedAt
            p[PLANNED_SEC] = state.plannedSecCurrentSegment
            p[EXTENSION_SEC] = state.extensionSecCurrentSegment
            p[END_AT] = state.endAtEpochMs
            p[REMAINING_PAUSED] = state.remainingMsWhenPaused
            p[ACCUM_RUN] = state.accumulatedRunMs
            p[LAST_RESUMED] = state.lastResumedAt
            p[CYCLE_INDEX] = state.cycleIndex
            p[TARGET_CYCLES] = state.targetCycles
            p[TOTAL_FOCUS] = state.totalFocusMs
            p[TOTAL_BREAK] = state.totalBreakMs
            p[EXT_FOCUS] = state.extensionsFocusMs
            p[CARRY_REMAINING] = state.carryRemainingMs
        }
    }

    suspend fun loadState(context: Context): TimerState {
        return try {
            val p = context.timerDataStore.data.first()

            fun str(key: Preferences.Key<String>): String? = p[key]

            val status = str(STATUS)
                ?.let { name -> TimerStatus.entries.firstOrNull { it.name == name } }
                ?: TimerStatus.IDLE

            val state = TimerState(
                sessionId = str(SESSION_ID) ?: "",
                mode = str(MODE)?.let { name -> SessionMode.entries.firstOrNull { it.name == name } },
                taskId = str(TASK_ID),
                taskTitleSnapshot = str(TASK_TITLE) ?: "",
                phase = str(PHASE)?.let { name -> SegmentType.entries.firstOrNull { it.name == name } },
                status = status,
                segmentIndex = p[SEGMENT_INDEX] ?: 0,
                segmentStartedAt = p[SEGMENT_STARTED_AT] ?: 0L,
                plannedSecCurrentSegment = p[PLANNED_SEC] ?: 0,
                extensionSecCurrentSegment = p[EXTENSION_SEC] ?: 0,
                endAtEpochMs = p[END_AT] ?: 0L,
                remainingMsWhenPaused = p[REMAINING_PAUSED] ?: 0L,
                accumulatedRunMs = p[ACCUM_RUN] ?: 0L,
                lastResumedAt = p[LAST_RESUMED] ?: 0L,
                cycleIndex = p[CYCLE_INDEX] ?: 1,
                targetCycles = p[TARGET_CYCLES] ?: 4,
                carryRemainingMs = p[CARRY_REMAINING] ?: 0L,
                totalFocusMs = p[TOTAL_FOCUS] ?: 0L,
                totalBreakMs = p[TOTAL_BREAK] ?: 0L,
                extensionsFocusMs = p[EXT_FOCUS] ?: 0L
            )

            // 🛟 tolerate half-written states (e.g. process died mid-save)
            if (state.status != TimerStatus.IDLE &&
                (state.sessionId.isBlank() || state.phase == null)
            ) {
                TimerState()
            } else state
        } catch (e: Exception) {
            e.printStackTrace()
            TimerState()
        }
    }

    suspend fun clear(context: Context) {
        context.timerDataStore.edit { it.clear() }
    }
}
