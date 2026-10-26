package com.dev.nudge.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class StreakResult(
    /** Days counted up to today (or up to yesterday while today is pending). */
    val current: Int,
    /** Longest run anywhere in the given set. */
    val best: Int
)

/**
 * 🔥 Pure streak logic (spec §8.3).
 *
 * A day is in [daysWithFocus] when it reached the streak threshold
 * (caller filters by streakMinMinutes) **or** when a task was completed
 * that day (see [completionDays]). All arithmetic is calendar-based
 * ([LocalDate]), never 24-hour millis — so DST/timezone boundaries can
 * never shift a day across a streak edge.
 */
object StreakCalculator {

    /**
     * 🎯 Local calendar days on which at least one task was completed,
     * derived from each task's `completedAt` epoch millis.
     *
     * Finishing a task counts toward the streak immediately — on the day it
     * happened — instead of only after the focus-time threshold is reached.
     * Null/zero timestamps (legacy data without completion time) are skipped.
     */
    fun completionDays(
        completedAtMillis: Collection<Long>,
        zone: ZoneId = ZoneId.systemDefault()
    ): Set<LocalDate> = completedAtMillis.mapNotNull { ms ->
        if (ms <= 0L) null
        else runCatching { Instant.ofEpochMilli(ms).atZone(zone).toLocalDate() }.getOrNull()
    }.toSet()

    /**
     * Same as above but from whole tasks — handles completions that predate
     * `completedAt` (tasks finished in v2 or restored from old backups):
     * those carry no timestamp, so they fall back to their planned day
     * ([Task.date], tasks are date-wise ⇒ the day they were finished),
     * skipping anything dated in the future.
     */
    fun completionDays(
        tasks: Collection<Task>,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault()
    ): Set<LocalDate> {
        val stamped = completionDays(tasks.mapNotNull { it.completedAt }, zone)
        val legacy = tasks.asSequence()
            .filter { (it.completedAt ?: 0L) <= 0L }
            .mapNotNull { t -> runCatching { LocalDate.parse(t.date) }.getOrNull() }
            .filter { !it.isAfter(today) }
            .toSet()
        return stamped + legacy
    }

    fun compute(daysWithFocus: Set<LocalDate>, today: LocalDate): StreakResult {
        if (daysWithFocus.isEmpty()) return StreakResult(0, 0)

        // Current streak: walk back from today; when today is not counted
        // yet, the streak is still ALIVE until the end of today if yesterday
        // was counted — it simply isn't part of the count yet.
        var current = 0
        var cursor = if (today in daysWithFocus) today else today.minusDays(1)
        if (cursor in daysWithFocus) {
            while (cursor in daysWithFocus) {
                current++
                cursor = cursor.minusDays(1)
            }
        }

        // Best streak: longest consecutive run over the whole history.
        var best = 0
        var run = 0
        var prev: LocalDate? = null
        for (day in daysWithFocus.sorted()) {
            run = if (prev != null && day == prev.plusDays(1)) run + 1 else 1
            if (run > best) best = run
            prev = day
        }

        return StreakResult(current = current, best = best)
    }
}