package com.dev.nudge.utils

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 🔥 Exact alarms for the Focus/Pomodoro timer.
 *
 * Uses its own action + request code so timer alarms never collide with
 * per-task reminder alarms from [NotificationScheduler].
 */
object TimerAlarmScheduler {

    const val ACTION_TIMER_FINISH = "com.dev.nudge.action.TIMER_FINISH"
    const val REQUEST_CODE = 77001
    const val SNOOZE_MILLIS = 5 * 60 * 1000L

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, TimerAlarmReceiver::class.java).apply {
            action = ACTION_TIMER_FINISH
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Schedules the "timer finished" alarm at [endAtMillis] (wall clock).
     * Returns false when exact alarms are not permitted (Android 12+).
     */
    fun schedule(context: Context, endAtMillis: Long): Boolean {
        return try {
            val alarmManager =
                context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                !alarmManager.canScheduleExactAlarms()
            ) {
                return false
            }

            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                endAtMillis,
                pendingIntent(context)
            )
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun cancel(context: Context) {
        try {
            val alarmManager =
                context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(pendingIntent(context))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun canScheduleExact(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager =
                context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.canScheduleExactAlarms()
        } else true
    }
}
