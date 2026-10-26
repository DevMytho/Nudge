package com.dev.nudge.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

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

    // ------------------------------------------------- completion days

    @Test
    fun `completing a task today starts the streak the same day`() {
        // Finishing a task counts on its own day — no focus minutes needed.
        val zone = ZoneId.of("UTC")
        val completedAt = ZonedDateTime.of(today.atTime(9, 30), zone)
            .toInstant().toEpochMilli()

        val counted = StreakCalculator.completionDays(listOf(completedAt), zone)
        assertEquals(setOf(today), counted)

        val r = StreakCalculator.compute(counted, today)
        assertEquals(1, r.current) // ✅ updated on the day it happened
        assertEquals(1, r.best)
    }

    @Test
    fun `completion and focus days merge into one run`() {
        val zone = ZoneId.of("UTC")
        val completedToday = ZonedDateTime.of(today.atTime(18, 0), zone)
            .toInstant().toEpochMilli()

        val counted =
            StreakCalculator.completionDays(listOf(completedToday), zone) +
                setOf(today.minusDays(1), today.minusDays(2)) // focus days

        val r = StreakCalculator.compute(counted, today)
        assertEquals(3, r.current)
        assertEquals(3, r.best)
    }

    @Test
    fun `invalid or missing completion timestamps are ignored`() {
        val zone = ZoneId.of("UTC")
        val counted = StreakCalculator.completionDays(listOf(0L, -5L), zone)
        assertEquals(emptySet<LocalDate>(), counted)
    }

    @Test
    fun `completion timestamp maps to the local calendar day`() {
        val zone = ZoneId.of("Asia/Kolkata") // UTC+5:30, no DST
        // 2026-10-24 22:00 IST == 2026-10-24 16:30 UTC — same day here,
        // but a UTC-zone conversion of 01:30 IST would roll to the previous day.
        val lateNight = ZonedDateTime.of(2026, 10, 25, 1, 30, 0, 0, zone)
            .toInstant().toEpochMilli()
        assertEquals(
            setOf(LocalDate.of(2026, 10, 25)),
            StreakCalculator.completionDays(listOf(lateNight), zone)
        )
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