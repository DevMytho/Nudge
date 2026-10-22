package com.dev.nudge

import com.dev.nudge.model.Task
import com.dev.nudge.model.TaskUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Deterministic tests for the pure task helpers.
 * A fixed clock ("now") is injected everywhere.
 */
class TaskUtilsTest {

    private val sdf = SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault())

    // 🕒 Fixed clock: 10-Jun-2026 10:00
    private val now: Date = sdf.parse("10-06-2026 10:00")!!

    private fun task(
        id: String = "id",
        title: String = "Task",
        date: String = "10-06-2026",
        time: String = "10:30",
        priority: String = "Low",
        isCritical: Boolean = false
    ) = Task(
        id = id,
        title = title,
        time = time,
        priority = priority,
        isCritical = isCritical,
        date = date
    )

    // ------------------------------------------------------------ getDisplayTime

    @Test
    fun `shows minutes left within the hour`() {
        val t = task(date = "10-06-2026", time = "10:30")
        assertEquals("30 min left", TaskUtils.getDisplayTime(t, now))
    }

    @Test
    fun `shows hours left within the day`() {
        val t = task(date = "10-06-2026", time = "14:00")
        assertEquals("4 hr left", TaskUtils.getDisplayTime(t, now))
    }

    @Test
    fun `shows days left within a week`() {
        val t = task(date = "13-06-2026", time = "10:00") // exactly 3 days
        assertEquals("3 day left", TaskUtils.getDisplayTime(t, now))
    }

    @Test
    fun `shows raw date beyond a week`() {
        val t = task(date = "20-06-2026", time = "10:00") // 10 days
        assertEquals("20-06-2026", TaskUtils.getDisplayTime(t, now))
    }

    @Test
    fun `overdue task shows due label with time`() {
        val t = task(date = "10-06-2026", time = "09:00")
        assertEquals("Due • 09:00", TaskUtils.getDisplayTime(t, now))
    }

    @Test
    fun `blank date or time shows empty string`() {
        assertEquals("", TaskUtils.getDisplayTime(task(date = "", time = "10:30"), now))
        assertEquals("", TaskUtils.getDisplayTime(task(date = "10-06-2026", time = ""), now))
    }

    @Test
    fun `unparseable date falls back to raw time`() {
        assertEquals(
            "10:30",
            TaskUtils.getDisplayTime(task(date = "garbage", time = "10:30"), now)
        )
    }

    // ---------------------------------------------------------------- grouping

    @Test
    fun `groups into today tomorrow and upcoming`() {
        val today1 = task(id = "1", date = "10-06-2026", time = "11:00")
        val today2 = task(id = "2", date = "10-06-2026", time = "23:00")
        val tomorrow = task(id = "3", date = "11-06-2026", time = "09:00")
        val upcoming = task(id = "4", date = "15-06-2026", time = "09:00")
        val past = task(id = "5", date = "09-06-2026", time = "09:00")

        val groups = TaskUtils.groupTasks(
            listOf(today1, today2, tomorrow, upcoming, past),
            now
        )

        assertEquals(listOf("1", "2"), groups.today.map { it.id })
        assertEquals(listOf("3"), groups.tomorrow.map { it.id })
        assertEquals(listOf("4"), groups.upcoming.map { it.id })
    }

    @Test
    fun `unparseable dates are ignored by grouping`() {
        val broken = task(id = "x", date = "not-a-date", time = "10:00")
        val groups = TaskUtils.groupTasks(listOf(broken), now)

        assertEquals(0, groups.today.size)
        assertEquals(0, groups.tomorrow.size)
        assertEquals(0, groups.upcoming.size)
    }

    @Test
    fun `today bucket is sorted critical first`() {
        val normalHigh = task(id = "h", priority = "High")
        val criticalLow = task(id = "c", priority = "Low", isCritical = true)

        val groups = TaskUtils.groupTasks(listOf(normalHigh, criticalLow), now)

        assertEquals(listOf("c", "h"), groups.today.map { it.id })
    }

    // -------------------------------------------------------------- reminders

    @Test
    fun `reminder fires for a future deadline`() {
        val t = task(date = "10-06-2026", time = "10:30")
        assertEquals(
            sdf.parse("10-06-2026 10:30")!!.time,
            TaskUtils.reminderTimeMillis(t, now)
        )
    }

    @Test
    fun `no reminder for a past deadline`() {
        val t = task(date = "10-06-2026", time = "09:00")
        assertNull(TaskUtils.reminderTimeMillis(t, now))
    }

    @Test
    fun `no reminder when date or time is blank`() {
        assertNull(TaskUtils.reminderTimeMillis(task(date = "", time = "10:30"), now))
        assertNull(TaskUtils.reminderTimeMillis(task(date = "10-06-2026", time = ""), now))
    }

    @Test
    fun `no reminder when deadline is unparseable`() {
        assertNull(TaskUtils.reminderTimeMillis(task(date = "garbage", time = "10:30"), now))
    }

    // ----------------------------------------------------------------- sorting

    @Test
    fun `sorts critical first then high medium low`() {
        val low = task(id = "low", priority = "Low")
        val med = task(id = "med", priority = "Medium")
        val high = task(id = "high", priority = "High")
        val criticalLow = task(id = "crit", priority = "Low", isCritical = true)

        val sorted = TaskUtils.sortTasks(listOf(low, med, high, criticalLow))

        assertEquals(listOf("crit", "high", "med", "low"), sorted.map { it.id })
    }

    @Test
    fun `two tasks with the same title stay distinct entries`() {
        val a = task(id = "a", title = "Same title")
        val b = task(id = "b", title = "Same title")

        val sorted = TaskUtils.sortTasks(listOf(a, b))

        assertEquals(2, sorted.size)
        assertEquals(setOf("a", "b"), sorted.map { it.id }.toSet())
    }
}
