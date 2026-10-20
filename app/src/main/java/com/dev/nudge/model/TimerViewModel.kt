package com.dev.nudge.model

import android.content.Context
import com.dev.nudge.data.TimerState
import com.dev.nudge.data.TimerStatus
import com.dev.nudge.data.TimerStorage
import com.dev.nudge.utils.TimerAlarmScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 🔥 Owns the Pomodoro timer.
 *
 * End-timestamp model: while RUNNING the remaining time is always derived
 * from `endAtMillis`, so the UI is correct after backgrounding, rotation
 * or process death. Every state change is persisted and the exact alarm is
 * scheduled/cancelled to match.
 *
 * Plain class (no lifecycle-viewmodel dep) — instantiate with remember {}.
 */
class TimerViewModel(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(TimerState())
    val state: StateFlow<TimerState> = _state

    private val _now = MutableStateFlow(System.currentTimeMillis())

    /** Latest wall-clock reading — the UI ticks this while running. */
    val now: StateFlow<Long> = _now

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** One-off user messages (e.g. exact-alarm permission missing). */
    val messages: SharedFlow<String> = _messages

    init {
        scope.launch {
            _state.value = TimerStorage.loadState(context)
            _now.value = System.currentTimeMillis()
        }
    }

    fun tick() {
        _now.value = System.currentTimeMillis()
    }

    fun start() {
        val s = _state.value
        val remainingNow = s.remainingAt(System.currentTimeMillis())
        if (remainingNow <= 0L) return

        val now = System.currentTimeMillis()
        val endAt = now + remainingNow

        persist(
            s.copy(
                status = TimerStatus.RUNNING,
                endAtMillis = endAt,
                remainingMillis = remainingNow
            )
        )

        if (!TimerAlarmScheduler.schedule(context, endAt)) {
            _messages.tryEmit("Allow exact alarms in Settings to get the timer alert ⏰")
        }
    }

    fun pause() {
        val s = _state.value
        val remainingNow = s.remainingAt(System.currentTimeMillis())
        persist(s.copy(status = TimerStatus.PAUSED, remainingMillis = remainingNow))
        TimerAlarmScheduler.cancel(context)
    }

    fun reset() {
        val s = _state.value
        persist(TimerState(focusedTaskId = s.focusedTaskId))
        TimerAlarmScheduler.cancel(context)
    }

    fun addTime(millis: Long) {
        val s = _state.value
        if (s.status == TimerStatus.RUNNING) {
            val newEnd = s.endAtMillis + millis
            persist(s.copy(endAtMillis = newEnd))
            if (!TimerAlarmScheduler.schedule(context, newEnd)) {
                _messages.tryEmit("Allow exact alarms in Settings to get the timer alert ⏰")
            }
        } else {
            persist(s.copy(remainingMillis = s.remainingMillis + millis))
        }
    }

    private fun persist(newState: TimerState) {
        _state.value = newState
        scope.launch { TimerStorage.saveState(context, newState) }
    }

    /** Stop coroutines (called when the activity dies). */
    fun close() {
        scope.cancel()
    }
}
