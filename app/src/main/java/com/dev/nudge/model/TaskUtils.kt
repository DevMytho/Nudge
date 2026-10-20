package com.dev.nudge.model

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Pure, testable task helpers.
 *
 * All time-dependent functions take an injectable `now` so unit tests can
 * use a fixed clock (see TaskUtilsTest).
 */
object TaskUtils {

    private const val DATE_TIME_PATTERN = "dd-MM-yyyy HH:mm"
    private const val DATE_PATTERN = "dd-MM-yyyy"

    fun parseDate(date: String): Date? = try {
        SimpleDateFormat(DATE_PATTERN, Locale.getDefault()).parse(date)
    } catch (e: Exception) {
        null
    }

    fun parseDateTime(date: String, time: String): Date? = try {
        SimpleDateFormat(DATE_TIME_PATTERN, Locale.getDefault()).parse("$date $time")
    } catch (e: Exception) {
        null
    }

    fun priorityValue(task: Task): Int = when (task.priority) {
        "High" -> 1
        "Medium" -> 2
        else -> 3
    }

    /** 🔥 Sort: critical tasks first, then High → Medium → Low. */
    fun sortTasks(list: List<Task>): List<Task> = list.sortedWith(
        compareBy<Task> { !it.isCritical }
            .thenBy { priorityValue(it) }
    )

    fun isSameDay(d1: Date, d2: Date): Boolean {
        val cal1 = Calendar.getInstance().apply { time = d1 }
        val cal2 = Calendar.getInstance().apply { time = d2 }

        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }

    data class TaskGroups(
        val today: List<Task>,
        val tomorrow: List<Task>,
        val upcoming: List<Task>
    )

    /** 🔥 Buckets tasks into Today / Tomorrow / Upcoming, each sorted. */
    fun groupTasks(tasks: List<Task>, now: Date = Date()): TaskGroups {
        val todayTasks = mutableListOf<Task>()
        val tomorrowTasks = mutableListOf<Task>()
        val upcomingTasks = mutableListOf<Task>()

        val tomorrowDate = Calendar.getInstance().apply {
            time = now
            add(Calendar.DAY_OF_YEAR, 1)
        }.time

        tasks.forEach { task ->
            val parsed = parseDate(task.date) ?: return@forEach

            when {
                isSameDay(parsed, now) -> todayTasks.add(task)
                isSameDay(parsed, tomorrowDate) -> tomorrowTasks.add(task)
                parsed.after(now) -> upcomingTasks.add(task)
            }
        }

        return TaskGroups(
            today = sortTasks(todayTasks),
            tomorrow = sortTasks(tomorrowTasks),
            upcoming = sortTasks(upcomingTasks)
        )
    }

    /** 🔥 Relative countdown text: "45 min left" / "Due • 10:30" / raw date. */
    fun getDisplayTime(task: Task, now: Date = Date()): String {

        if (task.date.isBlank() || task.time.isBlank()) return ""

        val taskDateTime = parseDateTime(task.date, task.time) ?: return task.time

        val diff = taskDateTime.time - now.time
        val minutes = diff / (1000 * 60)
        val hours = diff / (1000 * 60 * 60)
        val days = diff / (1000 * 60 * 60 * 24)

        return when {
            diff < 0 -> "Due • ${task.time}" // 🔥 clean past state
            minutes < 60 -> "$minutes min left"
            hours < 24 -> "$hours hr left"
            days < 7 -> "$days day left"
            else -> task.date
        }
    }
}
