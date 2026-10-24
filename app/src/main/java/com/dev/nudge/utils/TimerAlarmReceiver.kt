package com.dev.nudge.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dev.nudge.data.SettingsStorage
import com.dev.nudge.data.TimerStorage
import com.dev.nudge.R
import com.dev.nudge.data.session.SegmentType
import com.dev.nudge.data.session.SessionRepository
import com.dev.nudge.data.session.SessionState
import com.dev.nudge.model.TimerEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 🔥 Fired by [TimerAlarmScheduler] when the current phase reaches zero.
 *
 * Runs even on a cold start (the system starts a fresh process for this
 * broadcast), so it hosts the same reconcile the ViewModel runs: close the
 * finished segment with its planned end time, advance to the next phase
 * (paused), close the session if that was the last phase — then ring.
 */
class TimerAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TimerAlarmScheduler.ACTION_TIMER_FINISH) return

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            var preState: com.dev.nudge.data.TimerState? = null
            var result: TimerEngine.PhaseResult? = null
            try {
                val settings = SettingsStorage.load(context)
                val state = TimerStorage.loadState(context)
                val now = System.currentTimeMillis()

                preState = state
                val reconcileResult = TimerEngine.reconcile(state, now, settings)
                result = reconcileResult

                if (reconcileResult.changed) {
                    TimerStorage.saveState(context, reconcileResult.newState)
                    val repo = SessionRepository.getInstance(context)

                    reconcileResult.closedSegment?.let { repo.saveSegment(it) }

                    if (reconcileResult.sessionFinished) {
                        val sessionId =
                            reconcileResult.closedSegment?.sessionId ?: state.sessionId
                        if (sessionId.isNotBlank()) {
                            repo.sessionById(sessionId)?.let { row ->
                                repo.closeSession(row.copy(state = SessionState.FINISHED.name))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                // 🔔 ring regardless of what reconciled — the user expects the sound.
                //    Wording reflects the phase that just ended.
                val message = when {
                    result?.sessionFinished == true || preState?.isActive() != true ->
                        context.getString(R.string.notif_ring_session_done)
                    preState?.phase == SegmentType.FOCUS ->
                        context.getString(R.string.notif_ring_focus_end)
                    else ->
                        context.getString(R.string.notif_ring_break_over)
                }
                AlarmService.start(context, timerMessage = message)
                pendingResult.finish()
            }
        }
    }
}
