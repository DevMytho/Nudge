package com.dev.nudge.model

import android.content.Context
import com.dev.nudge.data.SettingsStorage
import com.dev.nudge.data.TimerState
import com.dev.nudge.data.TimerStatus
import com.dev.nudge.data.TimerStorage
import com.dev.nudge.data.UserSettings
import com.dev.nudge.data.session.FocusSessionEntity
import com.dev.nudge.data.session.SessionMode
import com.dev.nudge.data.session.SessionRepository
import com.dev.nudge.data.session.SessionSegmentEntity
import com.dev.nudge.data.session.SessionState
import com.dev.nudge.utils.NotificationHelper
import com.dev.nudge.utils.TimerAlarmScheduler
import com.dev.nudge.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Date

/** Summary of a finished session, shown on the finish card / done-sheet. */
data class TimerSessionSummary(
    val sessionId: String,
    val taskId: String?,
    val taskTitle: String,
    val focusSec: Int,
    val breakSec: Int,
    val extensionSec: Int,
    val cycles: Int
)

/**
 * ⏱ Hosts the shared [TimerEngine] for Focus and Pomodoro (spec §6).
 *
 * Owns: in-memory state + DataStore persistence on every transition,
 * exact-alarm arming/cancelling, Room session/segment writes, the ongoing
 * session notification and the one-off summary events for the UI.
 *
 * Process death: state is an end-timestamp model persisted on every
 * transition; [refresh] (called on resume) reconciles whatever happened while
 * the app was gone — the same reconcile TimerAlarmReceiver runs when the
 * phase alarm fires.
 */
class TimerViewModel(
    private val context: Context,
    private val sessions: SessionRepository,
    private val clock: AppClock = AppSystemClock
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(TimerState())
    val state: StateFlow<TimerState> = _state

    private val _now = MutableStateFlow(clock.nowMillis())

    /** Latest wall-clock reading — the UI ticks this while running. */
    val now: StateFlow<Long> = _now

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** One-off user messages (e.g. adjustment not allowed). */
    val messages: SharedFlow<String> = _messages

    private val _settings = MutableStateFlow(UserSettings())
    val settings: StateFlow<UserSettings> = _settings

    /**
     * Session-only length overrides set by the Pomodoro start screen
     * (spec §7). In-memory: they apply while this process lives; after
     * process death the next phase falls back to saved settings — noted
     * in docs/DECISIONS.md.
     */
    private val _sessionOverrides = MutableStateFlow<UserSettings?>(null)

    private fun engineSettings(): UserSettings =
        _sessionOverrides.value ?: _settings.value

    private val _summary = MutableSharedFlow<TimerSessionSummary>(extraBufferCapacity = 1)
    val summary: SharedFlow<TimerSessionSummary> = _summary

    /** taskId → focused seconds, straight from Room (Tasks + Focus tabs). */
    val focusSecondsByTask: StateFlow<Map<String, Int>> =
        sessions.observeFocusSecondsByTask()
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    init {
        scope.launch {
            _settings.value = SettingsStorage.load(context)
            refreshInternal()
        }
    }

    fun tick() {
        _now.value = clock.nowMillis()
    }

    // ----------------------------------------------------------- lifecycle

    /** Re-read persisted state and reconcile (call on ON_RESUME). */
    fun refresh() {
        scope.launch { refreshInternal() }
    }

    private suspend fun refreshInternal() {
        val loaded = TimerStorage.loadState(context)
        _settings.value = SettingsStorage.load(context)

        val result = TimerEngine.reconcile(loaded, clock.nowMillis(), engineSettings())
        if (result.changed) {
            applyResult(result)
        } else {
            // no phase ended — but storage may have been written by
            // TimerAlarmReceiver while this ViewModel was alive
            _state.value = result.newState
            if (!result.newState.isActive()) _sessionOverrides.value = null
        }
    }

    // ------------------------------------------------------------- actions

    fun startSession(
        mode: SessionMode,
        taskId: String?,
        taskTitle: String,
        lengthMinutes: Int?,          // null = open-ended stopwatch
        targetCycles: Int,
        overrides: UserSettings? = null // session-only lengths (Pomodoro)
    ) {
        scope.launch {
            _settings.value = SettingsStorage.load(context)
            _sessionOverrides.value = overrides
            val now = clock.nowMillis()
            val plannedSec = (lengthMinutes ?: 0).coerceAtLeast(0) * 60

            val fresh = TimerEngine.startSession(
                nowMillis = now,
                mode = mode,
                taskId = taskId,
                taskTitle = taskTitle,
                plannedSec = plannedSec,
                targetCycles = targetCycles
            )

            TimerStorage.saveState(context, fresh)
            _state.value = fresh

            sessions.startSession(
                FocusSessionEntity(
                    id = fresh.sessionId,
                    taskId = taskId,
                    taskTitleSnapshot = taskTitle,
                    mode = mode.name,
                    date = TaskUtils.todayIso(Date(now)),
                    startedAt = now,
                    state = SessionState.RUNNING.name
                )
            )

            armAlarmFor(fresh)
            updateSessionNotification(fresh)
        }
    }

    fun togglePause() {
        val s = _state.value
        val now = clock.nowMillis()
        val next = when (s.status) {
            TimerStatus.RUNNING -> TimerEngine.pause(s, now)
            TimerStatus.PAUSED -> TimerEngine.resume(s, now)
            TimerStatus.IDLE -> return
        }
        scope.launch {
            TimerStorage.saveState(context, next)
            _state.value = next
            armAlarmFor(next)
            updateSessionNotification(next)
        }
    }

    /** ±5 adjustment. @return true when applied. */
    fun adjust(deltaSec: Int): Boolean {
        val now = clock.nowMillis()
        val next = TimerEngine.adjust(_state.value, deltaSec, now) ?: run {
            if (deltaSec < 0) {
                _messages.tryEmit(
                    context.getString(R.string.adjust_negative_blocked)
                )
            }
            return false
        }
        scope.launch {
            TimerStorage.saveState(context, next)
            _state.value = next
            armAlarmFor(next)
        }
        return true
    }

    fun skipPhase() {
        scope.launch {
            val result = TimerEngine.skipPhase(
                state = _state.value,
                settings = engineSettings(),
                nowMillis = clock.nowMillis(),
                autoStart = _settings.value.autoStartNextPhase
            )
            applyResult(result)
        }
    }

    fun finishSession() {
        scope.launch {
            val result = TimerEngine.finishSession(_state.value, clock.nowMillis())
            applyResult(result)
        }
    }

    /** Focus mode: log the current block and start a short break. */
    fun takeBreak() {
        scope.launch {
            val breakMin = engineSettings().shortBreakMinutes
            val result = TimerEngine.takeBreak(
                state = _state.value,
                nowMillis = clock.nowMillis(),
                breakPlannedSec = breakMin * 60
            )
            applyResult(result)
        }
    }

    // ------------------------------------------------------------- helpers

    /** Persist an engine result: storage, Room segment, session end, summary. */
    private suspend fun applyResult(result: TimerEngine.PhaseResult) {
        TimerStorage.saveState(context, result.newState)
        _state.value = result.newState

        if (!result.changed) return

        result.closedSegment?.let { sessions.saveSegment(it) }

        if (result.sessionFinished) {
            _sessionOverrides.value = null
            val sessionId = result.closedSegment?.sessionId
                ?: result.totals?.sessionId
                ?: _state.value.sessionId
            if (sessionId.isNotBlank()) {
                sessions.sessionById(sessionId)?.let { row ->
                    sessions.closeSession(row.copy(state = SessionState.FINISHED.name))
                }
                emitSummary(sessionId, result.totals ?: TimerState())
            }
        }

        armAlarmFor(result.newState)
        updateSessionNotification(result.newState)
    }

    /** Live segment log of one session (Pomodoro tab). */
    fun segmentsForSession(sessionId: String): Flow<List<SessionSegmentEntity>> =
        sessions.observeSegmentsForSession(sessionId)

    private suspend fun emitSummary(sessionId: String, totals: TimerState) {
        val segments = sessions.segmentsForSession(sessionId)
        _summary.tryEmit(
            TimerSessionSummary(
                sessionId = sessionId,
                taskId = totals.taskId ?: segments.firstOrNull()?.taskId,
                taskTitle = totals.taskTitleSnapshot,
                focusSec = (totals.totalFocusMs / 1000L).toInt(),
                breakSec = (totals.totalBreakMs / 1000L).toInt(),
                extensionSec = (totals.extensionsFocusMs / 1000L).toInt(),
                cycles = segments.count {
                    it.segmentType == com.dev.nudge.data.session.SegmentType.FOCUS && it.completed
                }
            )
        )
    }

    private fun armAlarmFor(state: TimerState) {
        val shouldRing = state.status == TimerStatus.RUNNING &&
            state.plannedSecCurrentSegment > 0 &&
            state.endAtEpochMs > clock.nowMillis()
        if (shouldRing) {
            TimerAlarmScheduler.schedule(context, state.endAtEpochMs)
        } else {
            TimerAlarmScheduler.cancel(context)
        }
    }

    private fun updateSessionNotification(state: TimerState) {
        if (!state.isActive()) {
            NotificationHelper.cancelSessionNotification(context)
            return
        }
        val phaseLabel = when (state.phase) {
            com.dev.nudge.data.session.SegmentType.FOCUS ->
                context.getString(R.string.phase_focus)
            com.dev.nudge.data.session.SegmentType.SHORT_BREAK ->
                context.getString(R.string.phase_short_break)
            com.dev.nudge.data.session.SegmentType.LONG_BREAK ->
                context.getString(R.string.phase_long_break)
            null -> return
        }
        val timeText = if (state.plannedSecCurrentSegment <= 0) {
            "Stopwatch running"
        } else {
            val remainingMs = state.remainingAt(clock.nowMillis())
            String.format(
                java.util.Locale.US,
                "%02d:%02d",
                remainingMs / 60_000L,
                (remainingMs % 60_000L) / 1000L
            )
        }
        NotificationHelper.showSessionNotification(context, phaseLabel, timeText)
    }

    /** Stop coroutines (called when the activity dies). */
    fun close() {
        scope.cancel()
    }
}
