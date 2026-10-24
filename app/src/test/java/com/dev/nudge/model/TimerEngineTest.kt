package com.dev.nudge.model

import com.dev.nudge.data.TimerStatus
import com.dev.nudge.data.UserSettings
import com.dev.nudge.data.session.SegmentType
import com.dev.nudge.data.session.SessionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic tests for the pure timer engine (spec §11).
 * Time is always injected — nothing here reads the system clock.
 */
class TimerEngineTest {

    private val now = 1_770_000_000_000L // fixed instant
    private val settings = UserSettings() // focus 25, short 5, long 15, cycles 4

    private fun focusSession(
        plannedSec: Int = 25 * 60
    ) = TimerEngine.startSession(
        nowMillis = now,
        mode = SessionMode.FOCUS,
        taskId = "task-1",
        taskTitle = "Write report",
        plannedSec = plannedSec,
        targetCycles = settings.cyclesBeforeLongBreak
    )

    private fun pomodoroSession(
        plannedSec: Int = 25 * 60,
        targetCycles: Int = 4,
        cycleIndex: Int = 1
    ) = TimerEngine.startSession(
        nowMillis = now,
        mode = SessionMode.POMODORO,
        taskId = "task-1",
        taskTitle = "Write report",
        plannedSec = plannedSec,
        targetCycles = targetCycles
    ).copy(cycleIndex = cycleIndex)

    // ----------------------------------------------------------------- start

    @Test
    fun `start session runs with an end timestamp`() {
        val s = focusSession()

        assertEquals(TimerStatus.RUNNING, s.status)
        assertEquals(SegmentType.FOCUS, s.phase)
        assertEquals(now + 25 * 60 * 1000L, s.endAtEpochMs)
        assertEquals(25 * 60 * 1000L, s.remainingAt(now))
        assertTrue(s.sessionId.isNotBlank())
    }

    @Test
    fun `open-ended session has no end timestamp`() {
        val s = focusSession(plannedSec = 0)

        assertEquals(0L, s.endAtEpochMs)
        assertEquals(0, s.plannedSecCurrentSegment)
        assertTrue(s.isActive())
    }

    // -------------------------------------------------------- pause / resume

    @Test
    fun `pause freezes remaining and accumulates run time without double counting`() {
        var s = focusSession()

        s = TimerEngine.pause(s, now + 60_000L) // ran 60s
        assertEquals(TimerStatus.PAUSED, s.status)
        assertEquals(24 * 60 * 1000L, s.remainingMsWhenPaused)
        assertEquals(60_000L, s.accumulatedRunMs)

        // long pause must not count toward actual time
        s = TimerEngine.resume(s, now + 10 * 60_000L)
        assertEquals(TimerStatus.RUNNING, s.status)
        assertEquals(24 * 60 * 1000L, s.endAtEpochMs - (now + 10 * 60_000L))

        // 30s after resume: 60s before pause + 30s after
        assertEquals(90_000L, s.actualRunMsAt(now + 10 * 60_000L + 30_000L))
    }

    @Test
    fun `pause at zero remaining keeps zero`() {
        var s = focusSession(plannedSec = 60)
        s = TimerEngine.pause(s, now + 60_000L)
        assertEquals(0L, s.remainingMsWhenPaused)
    }

    // ------------------------------------------------------------- adjust ±5

    @Test
    fun `plus 5 extends end time and records the extension`() {
        val s = focusSession()
        val adjusted = TimerEngine.adjust(s, TimerEngine.ADJUST_SEC, now)

        assertNotNull(adjusted)
        assertEquals(s.endAtEpochMs + 300_000L, adjusted!!.endAtEpochMs)
        assertEquals(300, adjusted.extensionSecCurrentSegment)
        assertEquals(25 * 60, adjusted.plannedSecCurrentSegment) // planned stays
    }

    @Test
    fun `minus 5 shrinks remaining and records a negative extension`() {
        val s = focusSession()
        val adjusted = TimerEngine.adjust(s, -TimerEngine.ADJUST_SEC, now)

        assertNotNull(adjusted)
        assertEquals(s.endAtEpochMs - 300_000L, adjusted!!.endAtEpochMs)
        assertEquals(-300, adjusted.extensionSecCurrentSegment)
    }

    @Test
    fun `minus 5 is blocked at or below 5 minutes remaining`() {
        // paused exactly at the limit
        val atLimit = focusSession().copy(
            status = TimerStatus.PAUSED,
            remainingMsWhenPaused = TimerEngine.ADJUST_SEC * 1000L,
            endAtEpochMs = 0L
        )
        assertNull(TimerEngine.adjust(atLimit, -TimerEngine.ADJUST_SEC, now))

        // just above the limit → allowed
        val aboveLimit = focusSession().copy(
            status = TimerStatus.PAUSED,
            remainingMsWhenPaused = TimerEngine.ADJUST_SEC * 1000L + 1_000L,
            endAtEpochMs = 0L
        )
        val adjusted = TimerEngine.adjust(aboveLimit, -TimerEngine.ADJUST_SEC, now)
        assertNotNull(adjusted)
        assertEquals(1_000L, adjusted!!.remainingMsWhenPaused)
    }

    @Test
    fun `adjustments are not allowed for open-ended sessions`() {
        val s = focusSession(plannedSec = 0)
        assertNull(TimerEngine.adjust(s, TimerEngine.ADJUST_SEC, now))
        assertNull(TimerEngine.adjust(s, -TimerEngine.ADJUST_SEC, now))
    }

    @Test
    fun `adjust is a no-op when idle`() {
        assertNull(TimerEngine.adjust(com.dev.nudge.data.TimerState(), TimerEngine.ADJUST_SEC, now))
    }

    // ------------------------------------------------- actual vs planned sec

    @Test
    fun `completed segment logs planned plus extension as actual`() {
        val started = TimerEngine.adjust(focusSession(), TimerEngine.ADJUST_SEC, now)!!
        val endAt = started.endAtEpochMs

        val result = TimerEngine.reconcile(started, endAt + 60_000L, settings)

        assertTrue(result.changed)
        val seg = result.closedSegment!!
        assertEquals(25 * 60 + 300, seg.actualSec)
        assertEquals(300, seg.extensionSec)
        assertTrue(seg.completed)
        assertEquals(endAt, seg.endedAt) // stamped with the planned end time
    }

    @Test
    fun `skipped segment logs real running seconds not planned`() {
        val s = pomodoroSession()
        val skippedAt = now + 10_000L // stopped after 10 real seconds

        val result = TimerEngine.skipPhase(s, settings, skippedAt, autoStart = false)

        assertTrue(result.changed)
        val seg = result.closedSegment!!
        assertEquals(10, seg.actualSec)
        assertFalse(seg.completed)
        assertEquals(SegmentType.FOCUS, seg.segmentType)
    }

    // ------------------------------------------------------------- reconcile

    @Test
    fun `reconcile is a no-op while the phase is still running`() {
        val s = focusSession()
        val result = TimerEngine.reconcile(s, now + 60_000L, settings)

        assertFalse(result.changed)
        assertEquals(s, result.newState)
        assertNull(result.closedSegment)
    }

    @Test
    fun `reconcile closes an overdue focus phase and finishes the session`() {
        val s = focusSession()
        val result = TimerEngine.reconcile(s, now + 2 * 60 * 60 * 1000L, settings) // 2h late

        assertTrue(result.changed)
        assertTrue(result.sessionFinished)
        assertEquals(TimerStatus.IDLE, result.newState.status)
        assertEquals(25 * 60, result.closedSegment!!.actualSec)
        assertEquals(25 * 60 * 1000L, result.totals!!.totalFocusMs)
    }

    @Test
    fun `reconcile never auto-starts the next phase`() {
        val s = pomodoroSession()
        val result = TimerEngine.reconcile(s, now + 26 * 60 * 1000L, settings)

        assertTrue(result.changed)
        assertEquals(SegmentType.SHORT_BREAK, result.newState.phase)
        assertEquals(TimerStatus.PAUSED, result.newState.status) // paused, not running
        assertEquals(5 * 60 * 1000L, result.newState.remainingMsWhenPaused)
    }

    @Test
    fun `reconcile ignores open-ended sessions`() {
        val s = focusSession(plannedSec = 0)
        val result = TimerEngine.reconcile(s, now + 999_999L, settings)
        assertFalse(result.changed)
    }

    // ------------------------------------------------------ pomodoro flow

    @Test
    fun `focus ends → short break, paused when auto-start is off`() {
        val s = pomodoroSession()
        val result = TimerEngine.advanceAfterCompletedPhase(
            s, settings, now + 25 * 60 * 1000L, autoStart = false
        )

        assertEquals(SegmentType.SHORT_BREAK, result.newState.phase)
        assertEquals(TimerStatus.PAUSED, result.newState.status)
        assertEquals(5 * 60 * 1000L, result.newState.remainingMsWhenPaused)
        assertFalse(result.sessionFinished)
    }

    @Test
    fun `focus ends → short break, running when auto-start is on`() {
        val s = pomodoroSession()
        val end = now + 25 * 60 * 1000L
        val result = TimerEngine.advanceAfterCompletedPhase(
            s, settings, end, autoStart = true
        )

        assertEquals(SegmentType.SHORT_BREAK, result.newState.phase)
        assertEquals(TimerStatus.RUNNING, result.newState.status)
        assertEquals(end + 5 * 60 * 1000L, result.newState.endAtEpochMs)
    }

    @Test
    fun `after the Nth focus block comes the long break`() {
        val s = pomodoroSession(cycleIndex = 4, targetCycles = 4)
        val result = TimerEngine.advanceAfterCompletedPhase(
            s, settings, now + 25 * 60 * 1000L, autoStart = false
        )

        assertEquals(SegmentType.LONG_BREAK, result.newState.phase)
    }

    @Test
    fun `short break ends → next focus cycle`() {
        // simulate: focus done, break waiting, then break runs to completion
        val afterFocus = TimerEngine.advanceAfterCompletedPhase(
            pomodoroSession(), settings, now + 25 * 60 * 1000L, autoStart = false
        ).newState

        val afterBreak = TimerEngine.advanceAfterCompletedPhase(
            afterFocus, settings, now + 31 * 60 * 1000L, autoStart = false
        )

        assertEquals(SegmentType.FOCUS, afterBreak.newState.phase)
        assertEquals(2, afterBreak.newState.cycleIndex)
        assertEquals(25 * 60 * 1000L, afterBreak.newState.remainingMsWhenPaused)
    }

    @Test
    fun `long break ends → session finished with totals`() {
        val onLongBreak = TimerEngine.advanceAfterCompletedPhase(
            pomodoroSession(cycleIndex = 4, targetCycles = 4),
            settings,
            now + 25 * 60 * 1000L,
            autoStart = false
        ).newState

        val finished = TimerEngine.advanceAfterCompletedPhase(
            onLongBreak, settings, now + 41 * 60 * 1000L, autoStart = false
        )

        assertTrue(finished.sessionFinished)
        assertEquals(TimerStatus.IDLE, finished.newState.status)
        // this flow closed the 4th focus block (25m) + the long break (15m)
        assertEquals(25 * 60 * 1000L, finished.totals!!.totalFocusMs)
        assertEquals(15 * 60 * 1000L, finished.totals!!.totalBreakMs)
    }

    // ------------------------------------------------------------- finish

    @Test
    fun `manual finish logs real time and ends the session`() {
        var s = focusSession()
        s = TimerEngine.pause(s, now + 10 * 60_000L) // 10 min focused
        s = TimerEngine.resume(s, now + 12 * 60_000L)

        val result = TimerEngine.finishSession(s, now + 13 * 60_000L) // +1 min after resume

        assertTrue(result.sessionFinished)
        assertEquals(TimerStatus.IDLE, result.newState.status)
        assertEquals(11 * 60, result.closedSegment!!.actualSec) // 10 + 1, pause excluded
        assertFalse(result.closedSegment!!.completed)
        assertEquals(11 * 60 * 1000L, result.totals!!.totalFocusMs)
    }

    // ---------------------------------------------------------- focus breaks

    @Test
    fun `take break closes the focus block and remembers remaining time`() {
        val s = focusSession()

        val breakStart = now + 10 * 60_000L // 10 min focused in total
        val result = TimerEngine.takeBreak(s, breakStart, breakPlannedSec = 5 * 60)

        assertEquals(SegmentType.SHORT_BREAK, result.newState.phase)
        assertEquals(TimerStatus.RUNNING, result.newState.status)
        assertEquals(breakStart + 5 * 60 * 1000L, result.newState.endAtEpochMs)
        assertEquals(15 * 60 * 1000L, result.newState.carryRemainingMs) // 25m − 10m left
        assertEquals(10 * 60, result.closedSegment!!.actualSec)
        assertFalse(result.closedSegment!!.completed)
        assertEquals(10 * 60 * 1000L, result.totals!!.totalFocusMs)
    }

    @Test
    fun `focus resumes where it stopped after a break`() {
        val s = focusSession()

        val breakStart = now + 10 * 60_000L
        val onBreak = TimerEngine.takeBreak(s, breakStart, breakPlannedSec = 5 * 60).newState

        val resumed = TimerEngine.advanceAfterCompletedPhase(
            onBreak, settings, breakStart + 5 * 60 * 1000L, autoStart = false
        )

        assertEquals(SegmentType.FOCUS, resumed.newState.phase)
        assertEquals(15 * 60, resumed.newState.plannedSecCurrentSegment) // 25m − 10m done
        assertEquals(0L, resumed.newState.carryRemainingMs)
    }
}
