package com.dev.nudge.model

import android.content.Context
import com.dev.nudge.utils.NotificationScheduler

/**
 * 🔔 Abstraction over [NotificationScheduler] so [TaskViewModel] can cancel
 * and (re)arm reminders from one place, and so the decision logic stays
 * unit-testable (fake implementation in tests).
 */
interface ReminderScheduler {
    /** Arm the reminder (no-op if the task has no future reminder time). */
    fun scheduleIfFuture(task: Task)

    /** Cancel a previously armed reminder. Safe to call when none exists. */
    fun cancel(task: Task)
}

/** Production implementation backed by AlarmManager (setAlarmClock). */
class AndroidReminderScheduler(private val context: Context) : ReminderScheduler {

    override fun scheduleIfFuture(task: Task) {
        if (TaskUtils.reminderTimeMillis(task) == null) return
        NotificationScheduler.scheduleTask(
            context,
            task.id,
            task.title,
            task.date,
            task.dueTime ?: "",
            task.critical
        )
    }

    override fun cancel(task: Task) {
        NotificationScheduler.cancelTask(context, task.id, task.title)
    }
}
