package com.dev.nudge

import com.dev.nudge.model.Priority
import com.dev.nudge.model.Task
import com.dev.nudge.model.TaskUtils
import com.dev.nudge.model.normalized
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Deterministic tests for the pure task helpers.
 * A fixed clock ("now") is injected everywhere.
 *
 * Phase 1 note: dates moved from dd-MM-yyyy to ISO yyyy-MM-dd (spec §Time
 * handling), so the fixtures changed format. The behaviour assertions
 * (labels, relative countdowns, sorting) are intentionally unchanged.
 */
class TaskUtilsTest {

    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    // 🕒 Fixed clock: 10-Jun-2026 10:00
    private val now: Date = sdf.parse("2026-06-10 10:00")!!

    private fun task(
        id: String = "id",
        title: String = "Task",
        date: String = "2026-06-10",
        dueTime: String? = "10:30",
        priority: Priority = Priority.LOW,
        critical: Boolean = false,
        order: Int = 0,
        isCompleted: Boolean = false,
        createdAt: Long = 0L,
        estimatedMinutes: Int? = null,
        estimatedCycles: Int? = null,
        cycleMinutesSnapshot: Int? = null
    ) = Task(
        id = id,
        title = title,
        date = date,
        dueTime = dueTime,
        priority = priority.name,
        critical = critical,
        order = order,
        isCompleted = isCompleted,
        createdAt = createdAt,
        estimatedMinutes = estimatedMinutes,
        estimatedCycles = estimatedCycles,
        cycleMinutesSnapshot = cycleMinutesSnapshot
    )

    // ------------------------------------------------------------ getDisplayTime

    @Test
    fun `shows minutes left within the hour`() {
        val t = task(date = "2026-06-10", dueTime = "10:30")
        assertEquals("30 min left", TaskUtils.getDisplayTime(t, now))
    }

    @Test
    fun `shows hours left within the day`() {
        val t = task(date = "2026-06-10", dueTime = "14:00")
        assertEquals("4 hr left", TaskUtils.getDisplayTime(t, now))
    }

    @Test
    fun `shows days left within a week`() {
        val t = task(date = "2026-06-13", dueTime = "10:00") // exactly 3 days
        assertEquals("3 day left", TaskUtils.getDisplayTime(t, now))
    }

    @Test
    fun `shows raw date beyond a week`() {
        val t = task(date = "2026-06-20", dueTime = "10:00") // 10 days
        assertEquals("2026-06-20", TaskUtils.getDisplayTime(t, now))
    }

    @Test
    fun `overdue task shows due label with time`() {
        val t = task(date = "2026-06-10", dueTime = "09:00")
        assertEquals("Due • 09:00", TaskUtils.getDisplayTime(t, now))
    }

    @Test
    fun `no deadline shows empty string`() {
        assertEquals("", TaskUtils.getDisplayTime(task(date = "2026-06-10", dueTime = null), now))
        assertEquals("", TaskUtils.getDisplayTime(task(date = "", dueTime = "10:30"), now))
        assertEquals("", TaskUtils.getDisplayTime(task(date = "2026-06-10", dueTime = ""), now))
    }

    @Test
    fun `unparseable date falls back to raw time`() {
        assertEquals(
            "10:30",
            TaskUtils.getDisplayTime(task(date = "garbage", dueTime = "10:30"), now)
        )
    }

    // ------------------------------------------------------------ reminders

    @Test
    fun `reminder fires for a future deadline`() {
        val t = task(date = "2026-06-10", dueTime = "10:30")
        assertEquals(
            sdf.parse("2026-06-10 10:30")!!.time,
            TaskUtils.reminderTimeMillis(t, now)
        )
    }

    @Test
    fun `no reminder for a past deadline`() {
        val t = task(date = "2026-06-10", dueTime = "09:00")
        assertNull(TaskUtils.reminderTimeMillis(t, now))
    }

    @Test
    fun `no reminder when deadline is missing`() {
        assertNull(TaskUtils.reminderTimeMillis(task(date = "2026-06-10", dueTime = null), now))
        assertNull(TaskUtils.reminderTimeMillis(task(date = "2026-06-10", dueTime = ""), now))
        assertNull(TaskUtils.reminderTimeMillis(task(date = "", dueTime = "10:30"), now))
    }

    @Test
    fun `no reminder when deadline is unparseable`() {
        assertNull(TaskUtils.reminderTimeMillis(task(date = "garbage", dueTime = "10:30"), now))
    }

    // ---------------------------------------------------------------- sorting

    @Test
    fun `sorts critical first then high medium low`() {
        val low = task(id = "low", priority = Priority.LOW)
        val med = task(id = "med", priority = Priority.MEDIUM)
        val high = task(id = "high", priority = Priority.HIGH)
        val criticalLow = task(id = "crit", priority = Priority.LOW, critical = true)

        val sorted = TaskUtils.sortTasks(listOf(low, med, high, criticalLow))

        assertEquals(listOf("crit", "high", "med", "low"), sorted.map { it.id })
    }

    @Test
    fun `completion does not change position`() {
        // same list with one task completed must keep the same order
        val a = task(id = "a", priority = Priority.HIGH, order = 1)
        val b = task(id = "b", priority = Priority.HIGH, order = 2, isCompleted = true)
        val c = task(id = "c", priority = Priority.HIGH, order = 3)

        val sorted = TaskUtils.sortTasks(listOf(a, b, c))

        assertEquals(listOf("a", "b", "c"), sorted.map { it.id })
    }

    @Test
    fun `order breaks ties within the same priority`() {
        val second = task(id = "second", order = 2)
        val first = task(id = "first", order = 1)

        val sorted = TaskUtils.sortTasks(listOf(second, first))

        assertEquals(listOf("first", "second"), sorted.map { it.id })
    }

    @Test
    fun `two tasks with the same title stay distinct entries`() {
        val a = task(id = "a", title = "Same title")
        val b = task(id = "b", title = "Same title")

        val sorted = TaskUtils.sortTasks(listOf(a, b))

        assertEquals(2, sorted.size)
        assertEquals(setOf("a", "b"), sorted.map { it.id }.toSet())
    }

    // ---------------------------------------------------------- date helpers

    @Test
    fun `todayIso returns the ISO date for now`() {
        assertEquals("2026-06-10", TaskUtils.todayIso(now))
    }

    @Test
    fun `isoPlusDays crosses month boundaries`() {
        assertEquals("2026-06-30", TaskUtils.isoPlusDays("2026-06-29", 1))
        assertEquals("2026-07-01", TaskUtils.isoPlusDays("2026-06-30", 1))
        assertEquals("2026-06-09", TaskUtils.isoPlusDays("2026-06-10", -1))
    }

    @Test
    fun `legacy dd-MM-yyyy dates are migrated to ISO`() {
        assertEquals("2026-06-10", TaskUtils.normalizeLegacyDate("10-06-2026"))
        // already ISO → unchanged
        assertEquals("2026-06-10", TaskUtils.normalizeLegacyDate("2026-06-10"))
        // garbage → unchanged, never destroys data
        assertEquals("garbage", TaskUtils.normalizeLegacyDate("garbage"))
    }

    // ------------------------------------------------------------ grouping

    @Test
    fun `tasksForDate returns only that day, sorted`() {
        val todayHigh = task(id = "h", date = "2026-06-10", priority = Priority.HIGH)
        val todayLow = task(id = "l", date = "2026-06-10", priority = Priority.LOW)
        val tomorrow = task(id = "t", date = "2026-06-11")

        val list = TaskUtils.tasksForDate(listOf(todayLow, todayHigh, tomorrow), "2026-06-10")

        assertEquals(listOf("h", "l"), list.map { it.id })
    }

    @Test
    fun `carried over holds incomplete tasks from earlier days`() {
        val pastIncomplete = task(id = "past", date = "2026-06-08")
        val pastComplete = task(id = "done", date = "2026-06-08", isCompleted = true)
        val today = task(id = "today", date = "2026-06-10")
        val future = task(id = "future", date = "2026-06-12")

        val carried = TaskUtils.carriedOver(
            listOf(pastIncomplete, pastComplete, today, future),
            selectedIso = "2026-06-10"
        )

        assertEquals(listOf("past"), carried.map { it.id })
    }

    @Test
    fun `dayGroups are ordered past to future`() {
        val future = task(id = "f", date = "2026-06-12")
        val past = task(id = "p", date = "2026-06-08")
        val today = task(id = "t", date = "2026-06-10")

        val groups = TaskUtils.dayGroups(listOf(future, past, today))

        assertEquals(listOf("2026-06-08", "2026-06-10", "2026-06-12"), groups.map { it.first })
    }

    // ------------------------------------------------------------ estimates

    @Test
    fun `effective estimate prefers minutes field`() {
        val t = task(estimatedMinutes = 50, estimatedCycles = 2, cycleMinutesSnapshot = 25)
        assertEquals(50, TaskUtils.estimatedMinutesEffective(t))
    }

    @Test
    fun `effective estimate converts cycles via snapshot`() {
        val t = task(estimatedMinutes = null, estimatedCycles = 3, cycleMinutesSnapshot = 20)
        assertEquals(60, TaskUtils.estimatedMinutesEffective(t))
    }

    @Test
    fun `effective estimate is null without any estimate`() {
        assertNull(TaskUtils.estimatedMinutesEffective(task()))
        // cycles without a snapshot cannot convert
        assertNull(
            TaskUtils.estimatedMinutesEffective(
                task(estimatedCycles = 2, cycleMinutesSnapshot = null)
            )
        )
    }

    // -------------------------------------------------------------- format

    @Test
    fun `formatMinutes renders hours and minutes`() {
        assertEquals("0m", TaskUtils.formatMinutes(0))
        assertEquals("45m", TaskUtils.formatMinutes(45))
        assertEquals("1h", TaskUtils.formatMinutes(60))
        assertEquals("1h 40m", TaskUtils.formatMinutes(100))
    }

    // ------------------------------------------------------------ migration

    @Test
    fun `normalized migrates a legacy v2 task`() {
        val legacy = Task(
            id = "x",
            title = "Old",
            date = "10-06-2026",          // dd-MM-yyyy
            time = "10:30",               // legacy field → dueTime
            priority = "High",            // legacy capitalized
            legacyIsCritical = true,      // legacy flag (wire name "isCritical")
            isCompleted = false,
            createdAt = 1_770_000_000_000L
        ).normalized()

        assertEquals("2026-06-10", legacy.date)
        assertEquals("10:30", legacy.dueTime)
        assertEquals(Priority.HIGH, legacy.priorityLevel)
        assertEquals(true, legacy.critical)
        assertEquals(false, legacy.legacyIsCritical)
        assertEquals((1_770_000_000_000L / 60_000L).toInt(), legacy.order)
    }

    @Test
    fun `normalized keeps modern tasks intact`() {
        val modern = task(
            date = "2026-06-10",
            dueTime = "09:00",
            priority = Priority.MEDIUM,
            order = 7,
            createdAt = 123L
        ).normalized()

        assertEquals("2026-06-10", modern.date)
        assertEquals("09:00", modern.dueTime)
        assertEquals(Priority.MEDIUM, modern.priorityLevel)
        assertEquals(7, modern.order)
    }
}
