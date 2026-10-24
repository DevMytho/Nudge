package com.dev.nudge.model

import com.dev.nudge.data.session.SessionSegmentEntity

/** Estimate-accuracy classification (spec §8.1/§11). */
enum class EstimateAccuracy { NONE, ON, OVER }

/** Calendar day-cell state (spec §8.1). */
enum class DayCellState { EMPTY, PLANNED, ON_ESTIMATE, OVER_ESTIMATE }

/** Exact per-day totals, always derived from segments — never stored. */
data class DayTotals(
    val focusSec: Int,
    val breakSec: Int,
    val cycles: Int,
    val extensionSec: Int
)

/**
 * ⚙️ Pure statistics helpers (spec §8, §11): accuracy classification with
 * the 10 % tolerance, week-over-week change, day totals and chart inputs.
 * No Android types, no clock reads — everything is unit-testable.
 */
object StatsCalculator {

    /**
     * ON when actual ≤ estimate + 10 %, OVER when above, NONE when the
     * task/day has no estimate. Integer math, no float rounding:
     * actualSec × 10 ≤ estimateSec × 11.
     */
    fun classify(actualSec: Int, estimatedMinutes: Int?): EstimateAccuracy {
        if (estimatedMinutes == null || estimatedMinutes <= 0) return EstimateAccuracy.NONE
        val estimateSec = estimatedMinutes * 60
        return if (actualSec * 10 <= estimateSec * 11) EstimateAccuracy.ON
        else EstimateAccuracy.OVER
    }

    /**
     * Status ring of one calendar day (spec §8.1):
     * empty → planned-but-zero-focus grey → focused on/over estimate.
     * A day with focus but no estimate counts as ON (nothing to exceed) —
     * see docs/DECISIONS.md.
     */
    fun dayCellState(
        plannedTasks: Int,
        actualSec: Int,
        estimatedMinutes: Int?
    ): DayCellState {
        if (plannedTasks <= 0 && actualSec <= 0) return DayCellState.EMPTY
        if (actualSec <= 0) return DayCellState.PLANNED
        return if (classify(actualSec, estimatedMinutes) == EstimateAccuracy.OVER) {
            DayCellState.OVER_ESTIMATE
        } else {
            DayCellState.ON_ESTIMATE
        }
    }

    /** Sum focus/break seconds, completed cycles and net extensions. */
    fun dayTotals(segments: List<SessionSegmentEntity>): DayTotals {
        var focus = 0
        var brk = 0
        var cycles = 0
        var ext = 0
        for (s in segments) {
            if (s.segmentType == com.dev.nudge.data.session.SegmentType.FOCUS) {
                focus += s.actualSec
                ext += s.extensionSec
                if (s.completed) cycles++
            } else {
                brk += s.actualSec
            }
        }
        return DayTotals(focusSec = focus, breakSec = brk, cycles = cycles, extensionSec = ext)
    }

    /** This week vs last week in %; null when last week had no focus (no base). */
    fun weekOverWeekPercent(currentSec: Long, previousSec: Long): Int? {
        if (previousSec <= 0L) return null
        return (((currentSec - previousSec) * 100) / previousSec).toInt()
    }

    /** ISO date of the strongest focused day, ignoring days with no focus. */
    fun bestDay(focusSecByDate: Map<String, Int>): String? =
        focusSecByDate.filterValues { it > 0 }.maxByOrNull { it.value }?.key

    /** Total focused seconds of a month, given an ISO yyyy-MM prefix. */
    fun totalForMonth(focusSecByDate: Map<String, Int>, yearMonth: String): Int =
        focusSecByDate.filterKeys { it.startsWith(yearMonth) }.values.sum()
}