package com.dev.nudge.model

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Pure, testable task helpers.
 *
 * Time handling per spec: instants are epoch millis (UTC); dates are
 * ISO yyyy-MM-dd strings in the user's local zone. All time-dependent
 * functions take an injectable `now` so unit tests use a fixed clock.
 *
 * Legacy note: pre-v3 data used dd-MM-yyyy dates; [normalizeLegacyDate]
 * migrates them on every read boundary.
 */
object TaskUtils {

    private const val ISO_DATE_PATTERN = "yyyy-MM-dd"
    private const val ISO_DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm"
    private const val LEGACY_DATE_PATTERN = "dd-MM-yyyy" // pre-v3, migration only

    private val ISO_DATE_REGEX = Regex("^\\d{4}-\\d{2}-\\d{2}$")

    private fun isoDateFormat(): SimpleDateFormat =
        SimpleDateFormat(ISO_DATE_PATTERN, Locale.US).apply {
            timeZone = TimeZone.getDefault()
            isLenient = false
        }

    // ---------------------------------------------------------------- dates

    /** ISO date (local zone) for [now]. */
    fun todayIso(now: Date = Date()): String = isoDateFormat().format(now)

    /** Parse an ISO date (local midnight), or null when invalid. */
    fun parseIsoDate(date: String): Date? = try {
        if (!ISO_DATE_REGEX.matches(date)) null else isoDateFormat().parse(date)
    } catch (e: Exception) {
        null
    }

    /** The ISO date [days] after (or before, when negative) [date]. */
    fun isoPlusDays(date: String, days: Int): String {
        val parsed = parseIsoDate(date) ?: return date
        val cal = Calendar.getInstance().apply {
            time = parsed
            add(Calendar.DAY_OF_YEAR, days)
        }
        return isoDateFormat().format(cal.time)
    }

    /**
     * 🔄 Pre-v3 dates were dd-MM-yyyy. Already-ISO strings (and anything
     * unparseable) pass through unchanged — migration must never destroy data.
     */
    fun normalizeLegacyDate(raw: String): String {
        if (raw.isBlank() || ISO_DATE_REGEX.matches(raw)) return raw
        return try {
            val legacy = SimpleDateFormat(LEGACY_DATE_PATTERN, Locale.US).apply {
                isLenient = false
            }
            isoDateFormat().format(legacy.parse(raw) ?: return raw)
        } catch (e: Exception) {
            raw
        }
    }

    /** Local date+time for an ISO date and HH:mm time, or null when invalid. */
    fun parseDateTime(date: String, time: String): Date? = try {
        SimpleDateFormat(ISO_DATE_TIME_PATTERN, Locale.US).apply {
            timeZone = TimeZone.getDefault()
            isLenient = false
        }.parse("$date $time")
    } catch (e: Exception) {
        null
    }

    // -------------------------------------------------------------- sorting

    fun priorityValue(task: Task): Int = task.priorityLevel.sortWeight

    /**
     * 🔥 Sort: critical first, then High → Medium → Low, then stable
     * creation order. Completion state deliberately does NOT affect position
     * (tasks are struck through in place).
     */
    fun sortTasks(list: List<Task>): List<Task> = list.sortedWith(
        compareBy<Task> { !it.critical }
            .thenBy { priorityValue(it) }
            .thenBy { it.order }
            .thenBy { it.createdAt }
    )

    // ------------------------------------------------------------- grouping

    /** The selected day's tasks, sorted. */
    fun tasksForDate(tasks: List<Task>, isoDate: String): List<Task> =
        sortTasks(tasks.filter { it.date == isoDate })

    /**
     * 🔁 Incomplete tasks planned before [selectedIso] — shown in their own
     * "Carried over" section. Tasks are never moved silently.
     */
    fun carriedOver(tasks: List<Task>, selectedIso: String): List<Task> =
        sortTasks(tasks.filter { !it.isCompleted && it.date.isNotBlank() && it.date < selectedIso })

    /** Distinct planned dates, ascending (ISO strings sort lexicographically). */
    fun datesAscending(tasks: List<Task>): List<String> =
        tasks.map { it.date }.filter { it.isNotBlank() }.distinct().sorted()

    /**
     * "All" view: every planned date (past → today → future) with its tasks.
     * Returns pairs of (isoDate, sorted tasks).
     */
    fun dayGroups(tasks: List<Task>): List<Pair<String, List<Task>>> =
        datesAscending(tasks).map { date -> date to tasksForDate(tasks, date) }

    // ------------------------------------------------------------ estimates

    /**
     * Effective estimate in minutes: the canonical field, or cycles × the
     * cycle length snapshot taken when the estimate was entered.
     */
    fun estimatedMinutesEffective(task: Task): Int? =
        task.estimatedMinutes
            ?: task.estimatedCycles?.let { cycles ->
                task.cycleMinutesSnapshot?.let { minutes -> cycles * minutes }
            }

    // ------------------------------------------------------------ formatting

    /** "1h 40m" / "45m" / "0m" — used by the day summary and estimate chips. */
    fun formatMinutes(minutes: Int): String {
        val m = minutes.coerceAtLeast(0)
        val h = m / 60
        val rest = m % 60
        return when {
            h > 0 && rest > 0 -> "${h}h ${rest}m"
            h > 0 -> "${h}h"
            else -> "${rest}m"
        }
    }

    // -------------------------------------------------------------- reminders

    /**
     * 🔔 Epoch millis when a reminder for [task] should fire, or null when
     * the task has no usable deadline or its deadline is already in the past.
     *
     * Single source of truth for "should this task have an armed reminder?".
     */
    fun reminderTimeMillis(task: Task, now: Date = Date()): Long? {
        val due = task.dueTime
        if (task.date.isBlank() || due.isNullOrBlank()) return null
        val taskDateTime = parseDateTime(task.date, due) ?: return null
        return taskDateTime.time.takeIf { it > now.time }
    }

    /**
     * 🔥 Relative countdown text for the deadline: "45 min left" /
     * "Due • 10:30" / raw date. Empty string when the task has no deadline.
     */
    fun getDisplayTime(task: Task, now: Date = Date()): String {

        val due = task.dueTime
        if (task.date.isBlank() || due.isNullOrBlank()) return ""

        val taskDateTime = parseDateTime(task.date, due) ?: return due

        val diff = taskDateTime.time - now.time
        val minutes = diff / (1000 * 60)
        val hours = diff / (1000 * 60 * 60)
        val days = diff / (1000 * 60 * 60 * 24)

        return when {
            diff < 0 -> "Due • $due" // 🔥 clean past state
            minutes < 60 -> "$minutes min left"
            hours < 24 -> "$hours hr left"
            days < 7 -> "$days day left"
            else -> task.date
        }
    }
}
