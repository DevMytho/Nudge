package com.dev.nudge.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 🔥 Fired by [TimerAlarmScheduler] when the Pomodoro timer reaches zero.
 * Starts [AlarmService], which rings the chosen ringtone until the user
 * stops or snoozes it (or the auto-stop limit kicks in).
 */
class TimerAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TimerAlarmScheduler.ACTION_TIMER_FINISH) return

        AlarmService.start(context)
    }
}
