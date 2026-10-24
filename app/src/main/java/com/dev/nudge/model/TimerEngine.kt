package com.dev.nudge.model

import com.dev.nudge.data.TimerState
import com.dev.nudge.data.TimerStatus
import com.dev.nudge.data.UserSettings
import com.dev.nudge.data.session.SegmentType
import com.dev.nudge.data.session.SessionMode
import com.dev.nudge.data.session.SessionSegmentEntity
import java.util.UUID

/**
 * ⚙️ The shared, pure timer engine behind both Focus and Pomodoro (spec §6).
 *
 * Every function is a state transition with no side effects: the caller
 * (TimerViewModel, TimerAlarmReceiver) persists the returned state and the
 * returned segment. Time is always injected — never read from the system
 * clock inside the engine — so everything is unit-testable.
 *
 * Rules enforced here:
 *  - remaining while RUNNING = endAtEpochMs − now (survives process death)
 *  - actual segment time = running slices only (pauses excluded)
 *  - every ±5 press changes extensionSec of the current segment
 *  - −5 can never push remaining to ≤ 5 min (caller also disables the button)
 *  - reconcile closes a phase that should have ended while the app was dead,
 *    stamped with its planned end time, then pauses on the next phase
 */
object TimerEngine {

    const val ADJUST_SEC = 300
    private const val NEGATIVE_ADJUST_LIMIT_MS = ADJUST_SEC * 1000L

    /** Marker: the interrupted focus block was open-ended (no planned time). */
    const val CARRY_OPEN_ENDED = -1L

    /**
     * Outcome of an engine transition.
     *
     * @param newState persist this
     * @param closedSegment persist this segment (null when nothing closed)
     * @param sessionFinished the session ended — close the session row
     * @param totals the closed (pre-reset) state carrying the final totals,
     *   so the summary can be built even though [newState] is a fresh idle state
     */
    data class PhaseResult(
        val newState: TimerState,
        val closedSegment: SessionSegmentEntity? = null,
        val sessionFinished: Boolean = false,
        val totals: TimerState? = null
    ) {
        val changed: Boolean get() = closedSegment != null || sessionFinished
    }

    // ------------------------------------------------------------- start

    /** Start a brand-new session on its first FOCUS segment. */
    fun startSession(
        nowMillis: Long,
        mode: SessionMode,
        taskId: String?,
        taskTitle: String,
        plannedSec: Int,
        targetCycles: Int
    ): TimerState = TimerState(
        sessionId = UUID.randomUUID().toString(),
        mode = mode,
        taskId = taskId,
        taskTitleSnapshot = taskTitle,
        phase = SegmentType.FOCUS,
        status = TimerStatus.RUNNING,
        segmentIndex = 0,
        segmentStartedAt = nowMillis,
        plannedSecCurrentSegment = plannedSec,
        extensionSecCurrentSegment = 0,
        endAtEpochMs = if (plannedSec > 0) nowMillis + plannedSec * 1000L else 0L,
        remainingMsWhenPaused = 0L,
        accumulatedRunMs = 0L,
        lastResumedAt = nowMillis,
        cycleIndex = 1,
        targetCycles = targetCycles
    )

    // -------------------------------------------------------- pause/resume

    fun pause(state: TimerState, nowMillis: Long): TimerState {
        if (state.status != TimerStatus.RUNNING) return state
        return state.copy(
            status = TimerStatus.PAUSED,
            remainingMsWhenPaused = state.remainingAt(nowMillis),
            accumulatedRunMs = state.actualRunMsAt(nowMillis),
            lastResumedAt = 0L
        )
    }

    fun resume(state: TimerState, nowMillis: Long): TimerState {
        if (state.status != TimerStatus.PAUSED) return state
        val remaining = state.remainingMsWhenPaused.coerceAtLeast(0L)
        return state.copy(
            status = TimerStatus.RUNNING,
            endAtEpochMs = if (state.plannedSecCurrentSegment > 0) {
                nowMillis + remaining
            } else 0L,
            lastResumedAt = nowMillis
        )
    }

    // ------------------------------------------------------------ adjust ±5

    /** Adjust the current segment by [deltaSec] (+300 / −300), or null if not allowed. */
    fun adjust(state: TimerState, deltaSec: Int, nowMillis: Long): TimerState? {
        if (!state.isActive()) return null
        if (state.plannedSecCurrentSegment <= 0) return null // stopwatch: no ±5

        val remainingMs = when (state.status) {
            TimerStatus.RUNNING -> state.endAtEpochMs - nowMillis
            else -> state.remainingMsWhenPaused
        }
        if (deltaSec < 0 && remainingMs <= NEGATIVE_ADJUST_LIMIT_MS) return null

        val deltaMs = deltaSec * 1000L
        return when (state.status) {
            TimerStatus.RUNNING -> state.copy(
                endAtEpochMs = (state.endAtEpochMs + deltaMs).coerceAtLeast(nowMillis),
                extensionSecCurrentSegment = state.extensionSecCurrentSegment + deltaSec
            )

            TimerStatus.PAUSED -> state.copy(
                remainingMsWhenPaused = (state.remainingMsWhenPaused + deltaMs).coerceAtLeast(0L),
                extensionSecCurrentSegment = state.extensionSecCurrentSegment + deltaSec
            )

            TimerStatus.IDLE -> null
        }
    }

    // ------------------------------------------------------- segment closing

    /**
     * Close the current segment.
     *
     * @param completed ran to its end → actualSec = plannedSec + extensionSec;
     *   otherwise (skip/finish/abandon) actualSec = real running seconds.
     * @param endedAtOverride used by reconcile to stamp the planned end time.
     * @return advanced state (totals updated, counters reset) + the segment.
     */
    fun closeSegment(
        state: TimerState,
        nowMillis: Long,
        completed: Boolean,
        endedAtOverride: Long? = null
    ): Pair<TimerState, SessionSegmentEntity>? {
        val phase = state.phase ?: return null
        if (state.status == TimerStatus.IDLE) return null

        val endedAt = endedAtOverride ?: nowMillis
        val plannedSec = state.plannedSecCurrentSegment
        val actualSec = if (completed && plannedSec > 0) {
            (plannedSec + state.extensionSecCurrentSegment).coerceAtLeast(0)
        } else {
            ((state.actualRunMsAt(endedAt) / 1000L).toInt()).coerceAtLeast(0)
        }

        val segment = SessionSegmentEntity(
            id = UUID.randomUUID().toString(),
            sessionId = state.sessionId,
            taskId = state.taskId,
            index = state.segmentIndex,
            type = phase.name,
            plannedSec = plannedSec,
            extensionSec = state.extensionSecCurrentSegment,
            actualSec = actualSec,
            startedAt = state.segmentStartedAt,
            endedAt = endedAt,
            completed = completed
        )

        val isFocus = phase == SegmentType.FOCUS
        val advanced = state.copy(
            status = TimerStatus.PAUSED,
            segmentIndex = state.segmentIndex + 1,
            segmentStartedAt = 0L,
            plannedSecCurrentSegment = 0,
            extensionSecCurrentSegment = 0,
            endAtEpochMs = 0L,
            remainingMsWhenPaused = 0L,
            accumulatedRunMs = 0L,
            lastResumedAt = 0L,
            totalFocusMs = state.totalFocusMs + if (isFocus) actualSec * 1000L else 0L,
            totalBreakMs = state.totalBreakMs + if (!isFocus) actualSec * 1000L else 0L,
            extensionsFocusMs = state.extensionsFocusMs +
                if (isFocus) state.extensionSecCurrentSegment * 1000L else 0L
        )

        return advanced to segment
    }

    // -------------------------------------------------------- phase flow

    /** Planned seconds for a new phase of [type] from user settings. */
    fun plannedSecFor(type: SegmentType, settings: UserSettings): Int = when (type) {
        SegmentType.FOCUS -> settings.focusMinutes * 60
        SegmentType.SHORT_BREAK -> settings.shortBreakMinutes * 60
        SegmentType.LONG_BREAK -> settings.longBreakMinutes * 60
    }

    /**
     * Begin a phase: RUNNING when [autoStart], otherwise PAUSED waiting for a
     * tap (the segment's real running time starts only when the user starts).
     */
    fun beginPhase(
        state: TimerState,
        nowMillis: Long,
        phase: SegmentType,
        plannedSec: Int,
        autoStart: Boolean
    ): TimerState {
        val totalMs = plannedSec * 1000L
        return state.copy(
            phase = phase,
            status = if (autoStart) TimerStatus.RUNNING else TimerStatus.PAUSED,
            segmentStartedAt = nowMillis,
            plannedSecCurrentSegment = plannedSec,
            extensionSecCurrentSegment = 0,
            endAtEpochMs = if (autoStart && plannedSec > 0) nowMillis + totalMs else 0L,
            remainingMsWhenPaused = if (autoStart) 0L else totalMs,
            accumulatedRunMs = 0L,
            lastResumedAt = if (autoStart) nowMillis else 0L
        )
    }

    /**
     * The current phase ended normally (timer hit zero / skip). Close it and
     * move on — or finish the session when there is no next phase.
     *
     * @param autoStart honours the user's autoStartNextPhase setting (live
     *   transitions only — [reconcile] always pauses).
     */
    fun advanceAfterCompletedPhase(
        state: TimerState,
        settings: UserSettings,
        nowMillis: Long,
        autoStart: Boolean,
        endedAtOverride: Long? = null,
        completed: Boolean = true
    ): PhaseResult {
        val sourcePhase = state.phase
        if (sourcePhase == null || state.status == TimerStatus.IDLE) return PhaseResult(state)

        val mode = state.mode
        val (closed, segment) = closeSegment(state, nowMillis, completed = completed, endedAtOverride)
            ?: return PhaseResult(state)

        fun finished() = PhaseResult(
            newState = TimerState(),
            closedSegment = segment,
            sessionFinished = true,
            totals = closed
        )

        return when (mode) {
            SessionMode.FOCUS -> when (sourcePhase) {
                // focus block ran out → the session's goal is met
                SegmentType.FOCUS -> finished()
                // manual break ended → resume the interrupted focus block
                SegmentType.SHORT_BREAK, SegmentType.LONG_BREAK -> {
                    val carry = closed.carryRemainingMs
                    val nextPlanned = when {
                        carry == CARRY_OPEN_ENDED -> 0
                        else -> ((carry + 999) / 1000L).toInt().coerceAtLeast(1)
                    }
                    PhaseResult(
                        newState = beginPhase(
                            closed.copy(phase = SegmentType.FOCUS, carryRemainingMs = 0L),
                            nowMillis,
                            SegmentType.FOCUS,
                            nextPlanned,
                            autoStart
                        ),
                        closedSegment = segment,
                        totals = closed
                    )
                }
            }

            SessionMode.POMODORO -> when (sourcePhase) {
                // after N focus blocks → long break, else short break
                SegmentType.FOCUS -> {
                    val next = if (state.cycleIndex % state.targetCycles == 0) {
                        SegmentType.LONG_BREAK
                    } else {
                        SegmentType.SHORT_BREAK
                    }
                    PhaseResult(
                        newState = beginPhase(
                            closed.copy(phase = next),
                            nowMillis,
                            next,
                            plannedSecFor(next, settings),
                            autoStart
                        ),
                        closedSegment = segment,
                        totals = closed
                    )
                }

                SegmentType.SHORT_BREAK -> {
                    val next = SegmentType.FOCUS
                    PhaseResult(
                        newState = beginPhase(
                            closed.copy(phase = next, cycleIndex = state.cycleIndex + 1),
                            nowMillis,
                            next,
                            plannedSecFor(next, settings),
                            autoStart
                        ),
                        closedSegment = segment,
                        totals = closed
                    )
                }

                // all target cycles done
                SegmentType.LONG_BREAK -> finished()
            }

            null -> PhaseResult(state)
        }
    }

    /**
     * 🔁 Reconcile after process death / reboot / missed alarm: if the
     * current phase should have already ended, close it stamped with its
     * planned end time and pause on the next phase (reconcile NEVER
     * auto-starts — see docs/DECISIONS.md).
     */
    fun reconcile(
        state: TimerState,
        nowMillis: Long,
        settings: UserSettings
    ): PhaseResult {
        if (state.status != TimerStatus.RUNNING || state.phase == null) return PhaseResult(state)
        if (state.plannedSecCurrentSegment <= 0) return PhaseResult(state) // open-ended
        if (state.endAtEpochMs > nowMillis) return PhaseResult(state)      // still running

        return advanceAfterCompletedPhase(
            state = state,
            settings = settings,
            nowMillis = nowMillis,
            autoStart = false,
            endedAtOverride = state.endAtEpochMs
        )
    }

    // ------------------------------------------------------------ focus-mode

    /**
     * 🧘 Focus mode "Take a break": close the current focus block (logged,
     * not completed) and start a SHORT_BREAK that remembers how much focus
     * time was left ([TimerState.carryRemainingMs]), so focus resumes where
     * it stopped once the break ends.
     */
    fun takeBreak(
        state: TimerState,
        nowMillis: Long,
        breakPlannedSec: Int
    ): PhaseResult {
        if (state.mode != SessionMode.FOCUS || state.phase != SegmentType.FOCUS) {
            return PhaseResult(state)
        }
        val carry = if (state.plannedSecCurrentSegment <= 0) {
            CARRY_OPEN_ENDED
        } else {
            state.remainingAt(nowMillis)
        }

        val (closed, segment) = closeSegment(state, nowMillis, completed = false)
            ?: return PhaseResult(state)

        val totalMs = breakPlannedSec * 1000L
        val nextState = closed.copy(
            phase = SegmentType.SHORT_BREAK,
            status = TimerStatus.RUNNING,
            segmentStartedAt = nowMillis,
            plannedSecCurrentSegment = breakPlannedSec,
            extensionSecCurrentSegment = 0,
            endAtEpochMs = nowMillis + totalMs,
            remainingMsWhenPaused = 0L,
            accumulatedRunMs = 0L,
            lastResumedAt = nowMillis,
            carryRemainingMs = carry
        )
        return PhaseResult(newState = nextState, closedSegment = segment, totals = closed)
    }

    // ------------------------------------------------------------- skipping

    /**
     * ⏭ Pomodoro "Skip phase": close the current segment with its real
     * actualSec (completed = false) and advance.
     */
    fun skipPhase(
        state: TimerState,
        settings: UserSettings,
        nowMillis: Long,
        autoStart: Boolean
    ): PhaseResult {
        if (state.mode != SessionMode.POMODORO) return PhaseResult(state)
        return advanceAfterCompletedPhase(
            state = state,
            settings = settings,
            nowMillis = nowMillis,
            autoStart = autoStart,
            endedAtOverride = null,
            completed = false
        )
    }

    /**
     * 🏁 Manual finish: close the current segment for real and end the session.
     * [PhaseResult.totals] carries the final totals for the summary card.
     */
    fun finishSession(state: TimerState, nowMillis: Long): PhaseResult {
        if (state.phase == null || state.status == TimerStatus.IDLE) return PhaseResult(state)
        val (closed, segment) = closeSegment(state, nowMillis, completed = false)
            ?: return PhaseResult(state)
        return PhaseResult(
            newState = TimerState(),
            closedSegment = segment,
            sessionFinished = true,
            totals = closed
        )
    }
}
