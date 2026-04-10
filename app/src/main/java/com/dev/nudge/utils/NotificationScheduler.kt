package com.dev.nudge.utils

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.text.SimpleDateFormat
import java.util.*

object NotificationScheduler {

    @SuppressLint("ScheduleExactAlarm")
    fun scheduleTask(
        context: Context,
        title: String,
        date: String,
        time: String
    ) {

        try {

            if (date.isBlank() || time.isBlank()) return

            val sdf = SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault())
            val taskDateTime = sdf.parse("$date $time") ?: return

            val triggerTime = taskDateTime.time

            if (triggerTime <= System.currentTimeMillis()) return

            val intent = Intent(context, NotificationReceiver::class.java).apply {
                putExtra("title", title)
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                title.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            )

            println("✅ NOTIFICATION SCHEDULED AT: $triggerTime")

            println("DATE = $date TIME = $time")
            println("TRIGGER TIME = $triggerTime NOW = ${System.currentTimeMillis()}")

        } catch (e: Exception) {
            e.printStackTrace() // 🔥 prevents crash
        }
    }



}