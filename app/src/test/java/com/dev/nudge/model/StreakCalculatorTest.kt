package com.dev.nudge.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * Deterministic streak tests (spec §11): gaps, today pending, DST edges,
 * best streak. Pure [LocalDate] inputs — no clock, no timezone.
 */
class StreakCalculatorTest {

    private val today: LocalDate = LocalDate.of(2026, 10, 24)

    private fun run(vararg days: LocalDate) =
        StreakCalculator.compute(days.toSet(), today)

    @Test
    fun `empty history → no streaks`() {
        val r = StreakCalculator.compute(emptySet(), today)
        assertEquals(0, r.current)
        assertEquals(0, r.best)
    }

    @Test
    fun `consecutive days ending today count fully`() {
        val r = run(
            today, today.minusDays(1), today.minusDays(2), today.minusDays(3)
        )
        assertEquals(4, r.current)
        assertEquals(4, r.best)
    }

    @Test
    fun `today pending but yesterday counted → streak still alive`() {
        val r = run(today.minusDays(1), today.minusDays(2))
        assertEquals(2, r.current) // alive, today not added yet
        assertEquals(2, r.best)
    }

    @Test
    fun `gap yesterday breaks the current streak but best survives`() {
        val r = run(today.minusDays(2), today.minusDays(3), today.minusDays(4))
        // today and yesterday missing → current is dead
        assertEquals(0, r.current)
        assertEquals(3, r.best)
    }

    @Test
    fun `gap inside history picks the longest run as best`() {
        val r = run(
            today,                       // 1 (current only)
            today.minusDays(3),
            today.minusDays(4),
            today.minusDays(5),
            today.minusDays(6)           // 4-run wins
        )
        assertEquals(1, r.current)
        assertEquals(4, r.best)
    }

    @Test
    fun `single day counts as a streak of one`() {
        val r = run(today)
        assertEquals(1, r.current)
        assertEquals(1, r.best)
    }

    @Test
    fun `DST boundary days do not shift the streak`() {
        // 2026-03-08 is the US spring-forward day (23h day); consecutive
        // calendar days across it must count one by one, no off-by-one.
        val d0 = LocalDate.of(2026, 3, 6)
        val end = d0.plusDays(4) // 2026-03-10
        val r = StreakCalculator.compute(
            setOf(d0, d0.plusDays(1), d0.plusDays(2), d0.plusDays(3), end),
            end
        )
        assertEquals(5, r.current)
        assertEquals(5, r.best)
    }

    @Test
    fun `best is computed from history before the current run`() {
        val r = run(
            today, today.minusDays(1),           // current = 2
            today.minusDays(10),
            today.minusDays(9),
            today.minusDays(8),
            today.minusDays(7),
            today.minusDays(6)                   // best = 5
        )
        assertEquals(2, r.current)
        assertEquals(5, r.best)
    }
}