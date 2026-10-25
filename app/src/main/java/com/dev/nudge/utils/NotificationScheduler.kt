package com.dev.nudge.utils

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.dev.nudge.MainActivity
import com.dev.nudge.model.TaskUtils

object NotificationScheduler {

    private const val TAG = "NotifScheduler"
    private const val ACTION_TASK_REMINDER = "com.dev.nudge.action.TASK_REMINDER"

    /**
     * Schedules a reminder for a task.
     *
     * Uses [AlarmManager.setAlarmClock]: it needs NO runtime permission, is
     * always exact, wakes the device, survives process death (swipe-away) and
     * is exempt from the Android 12+ background-FGS-start restrictions — so
     * the reminder fires even when the app was killed. [BootReceiver]
     * reschedules after a reboot.
     *
     * The task's stable id backs the request code + intent data URI, so two
     * tasks with the same title get independent alarms.
     */
    fun scheduleTask(
        context: Context,
        taskId: String,
        title: String,
        date: String,
        time: String,
        isCritical: Boolean = false
    ) {

        try {

            if (date.isBlank() || time.isBlank()) return

            val triggerTime = reminderTriggerMillis(date, time) ?: return

            val identity = taskId.ifBlank { title }

            val intent = Intent(context, NotificationReceiver::class.java).apply {
                action = ACTION_TASK_REMINDER
                // 🔑 unique per task → unique PendingIntent, even for equal titles
                data = Uri.parse("nudge://reminder/$identity")
                putExtra("title", title)
                putExtra("critical", isCritical)
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                identity.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Tapping the system's "upcoming alarm" entry opens the app
            val showIntent = PendingIntent.getActivity(
                context,
                identity.hashCode() xor 0x5F5F,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(triggerTime, showIntent),
                pendingIntent
            )

            Log.d(TAG, "Reminder (re)scheduled for $triggerTime task='$title'")

        } catch (e: Exception) {
            Log.w(TAG, "Failed to schedule reminder for '$title'", e)
        }
    }

    /** Cancels a previously scheduled reminder (by task identity). */
    fun cancelTask(context: Context, taskId: String, title: String) {
        try {
            val identity = taskId.ifBlank { title }
            val intent = Intent(context, NotificationReceiver::class.java).apply {
                action = ACTION_TASK_REMINDER
                data = Uri.parse("nudge://reminder/$identity")
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                identity.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(pendingIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

/**
 * 🔔 Epoch millis when a reminder for [date] + [time] (HH:mm) should fire,
 * or null when it can't be parsed or is already in the past.
 *
 * 🔑 Parses ISO `yyyy-MM-dd` (what [Task.date] stores since pre-v3) and
 * defensively tolerates a legacy `dd-MM-yyyy` value. This file used to parse
 * with `dd-MM-yyyy` after dates migrated to ISO — lenient parsing turned
 * "2026-10-25" into year 0025, the `> now` check failed and EVERY reminder
 * was silently dropped. Keep in sync with [TaskUtils.parseDateTime].
 */
fun reminderTriggerMillis(
    date: String,
    time: String,
    now: Long = System.currentTimeMillis()
): Long? {
    if (date.isBlank() || time.isBlank()) return null
    val normalizedDate = TaskUtils.normalizeLegacyDate(date)
    val at = TaskUtils.parseDateTime(normalizedDate, time) ?: return null
    return at.time.takeIf { it > now }
}
