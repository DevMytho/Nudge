package com.dev.nudge.utils

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import java.text.SimpleDateFormat
import java.util.*

object NotificationScheduler {

    private const val TAG = "NotifScheduler"
    private const val ACTION_TASK_REMINDER = "com.dev.nudge.action.TASK_REMINDER"

    /**
     * Schedules a reminder for a task.
     *
     * - Uses the task's stable id for the request code + intent data URI, so
     *   two tasks with the same title get independent alarms.
     * - Exact alarm when permitted; otherwise falls back to an inexact alarm
     *   so the reminder still fires (Android 12+ doesn't grant
     *   SCHEDULE_EXACT_ALARM automatically).
     * - Alarms survive process death; BootReceiver reschedules after reboot.
     */
    @SuppressLint("ScheduleExactAlarm")
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

            val sdf = SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault())
            val taskDateTime = sdf.parse("$date $time") ?: return

            val triggerTime = taskDateTime.time

            if (triggerTime <= System.currentTimeMillis()) return

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

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    alarmManager.canScheduleExactAlarms()

            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
                Log.d(TAG, "Exact reminder set for $triggerTime")
            } else {
                // 🔁 Exact alarms not permitted — still remind, just less precisely
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
                Log.d(TAG, "Exact alarm not permitted — inexact reminder set")
            }

        } catch (e: Exception) {
            e.printStackTrace() // 🔥 prevents crash
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
