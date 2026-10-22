package com.dev.nudge.model

/**
 * ⏰ Injectable clock so every piece of "day" logic is deterministic in
 * unit tests. The default implementation reads the system wall clock.
 */
fun interface AppClock {
    fun nowMillis(): Long
}

/** Production clock (wall clock — the same one AlarmManager RTC uses). */
object AppSystemClock : AppClock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
