package com.dev.nudge.model

import com.dev.nudge.data.session.SegmentType
import com.dev.nudge.data.session.SessionSegmentEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Spec §11: accuracy classification incl. the 10 % tolerance, totals, stats. */
class StatsCalculatorTest {

    private fun seg(
        type: SegmentType,
        actualSec: Int,
        extensionSec: Int = 0,
        completed: Boolean = false,
        index: Int = 0
    ) = SessionSegmentEntity(
        id = "s$index",
        sessionId = "sess",
        taskId = "t1",
        index = index,
        type = type.name,
        plannedSec = 1500,
        extensionSec = extensionSec,
        actualSec = actualSec,
        startedAt = 0L,
        completed = completed
    )

    // ------------------------------------------------------ classification

    @Test
    fun `no estimate → NONE`() {
        assertEquals(EstimateAccuracy.NONE, StatsCalculator.classify(1800, null))
        assertEquals(EstimateAccuracy.NONE, StatsCalculator.classify(1800, 0))
        assertEquals(EstimateAccuracy.NONE, StatsCalculator.classify(1800, -5))
    }

    @Test
    fun `within estimate → ON`() {
        assertEquals(EstimateAccuracy.ON, StatsCalculator.classify(1500, 25)) // exact
        assertEquals(EstimateAccuracy.ON, StatsCalculator.classify(900, 25))  // under
    }

    @Test
    fun `exactly +10% still counts as ON, above it is OVER`() {
        // 25 min = 1500 s; +10 % = 1650 s
        assertEquals(EstimateAccuracy.ON, StatsCalculator.classify(1650, 25))
        assertEquals(EstimateAccuracy.OVER, StatsCalculator.classify(1651, 25))
        assertEquals(EstimateAccuracy.OVER, StatsCalculator.classify(3000, 25))
    }

    // ---------------------------------------------------------- day cells

    @Test
    fun `day cell states follow the spec`() {
        assertEquals(
            DayCellState.EMPTY,
            StatsCalculator.dayCellState(plannedTasks = 0, actualSec = 0, estimatedMinutes = null)
        )
        assertEquals(
            DayCellState.PLANNED,
            StatsCalculator.dayCellState(plannedTasks = 3, actualSec = 0, estimatedMinutes = 60)
        )
        assertEquals(
            DayCellState.ON_ESTIMATE,
            StatsCalculator.dayCellState(plannedTasks = 3, actualSec = 3600, estimatedMinutes = 60)
        )
        assertEquals(
            DayCellState.OVER_ESTIMATE,
            StatsCalculator.dayCellState(plannedTasks = 3, actualSec = 7000, estimatedMinutes = 60)
        )
        // focused with no estimate → nothing to exceed (decision in DECISIONS.md)
        assertEquals(
            DayCellState.ON_ESTIMATE,
            StatsCalculator.dayCellState(plannedTasks = 0, actualSec = 600, estimatedMinutes = null)
        )
    }

    // ------------------------------------------------------- day totals

    @Test
    fun `day totals sum focus, break, cycles and extensions`() {
        val segments = listOf(
            seg(SegmentType.FOCUS, actualSec = 1500, extensionSec = 300, completed = true, index = 0),
            seg(SegmentType.SHORT_BREAK, actualSec = 240, index = 1),
            seg(SegmentType.FOCUS, actualSec = 1060, completed = true, index = 2),
            seg(SegmentType.SHORT_BREAK, actualSec = 280, index = 3),
            seg(SegmentType.FOCUS, actualSec = 100, extensionSec = -60, completed = false, index = 4)
        )
        val t = StatsCalculator.dayTotals(segments)
        assertEquals(1500 + 1060 + 100, t.focusSec)
        assertEquals(240 + 280, t.breakSec)
        assertEquals(2, t.cycles) // only completed focus blocks count
        assertEquals(300 - 60, t.extensionSec)
    }

    // ------------------------------------------------------------- stats

    @Test
    fun `week over week percentage`() {
        assertEquals(50, StatsCalculator.weekOverWeekPercent(150, 100))
        assertEquals(-50, StatsCalculator.weekOverWeekPercent(50, 100))
        assertNull(StatsCalculator.weekOverWeekPercent(50, 0)) // no base
        assertEquals(0, StatsCalculator.weekOverWeekPercent(100, 100))
    }

    @Test
    fun `best day ignores days without focus`() {
        val map = mapOf("2026-10-20" to 0, "2026-10-21" to 3600, "2026-10-22" to 7200)
        assertEquals("2026-10-22", StatsCalculator.bestDay(map))
        assertNull(StatsCalculator.bestDay(mapOf("2026-10-20" to 0)))
        assertNull(StatsCalculator.bestDay(emptyMap()))
    }

    @Test
    fun `month total only sums the requested month`() {
        val map = mapOf(
            "2026-09-30" to 1000,
            "2026-10-01" to 2000,
            "2026-10-15" to 3000,
            "2026-11-01" to 4000
        )
        assertEquals(5000, StatsCalculator.totalForMonth(map, "2026-10"))
    }
}