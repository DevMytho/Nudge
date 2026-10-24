package com.dev.nudge.model

import java.time.LocalDate

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
 * (caller filters by streakMinMinutes). All arithmetic is calendar-based
 * ([LocalDate]), never 24-hour millis — so DST/timezone boundaries can
 * never shift a day across a streak edge.
 */
object StreakCalculator {

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