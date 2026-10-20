package com.dev.nudge.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dev.nudge.data.TaskStorage
import com.dev.nudge.data.TimerStatus
import com.dev.nudge.data.TimerStorage
import com.dev.nudge.data.FirestoreRepository
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 🔁 Alarms don't survive a reboot — reschedule them:
 *  - the Pomodoro timer alarm, if a timer is still running in the future
 *  - task reminders for incomplete future tasks (guest + Firestore)
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // ⏱ running timer
                val timer = TimerStorage.loadState(context)
                if (timer.status == TimerStatus.RUNNING &&
                    timer.endAtMillis > System.currentTimeMillis()
                ) {
                    TimerAlarmScheduler.schedule(context, timer.endAtMillis)
                }

                // 📝 task reminders
                val localTasks = TaskStorage.loadTasks(context)

                val remoteTasks = try {
                    if (FirebaseAuth.getInstance().currentUser != null) {
                        FirestoreRepository.getTasks()
                    } else emptyList()
                } catch (e: Exception) {
                    emptyList()
                }

                (localTasks + remoteTasks).forEach { task ->
                    NotificationScheduler.scheduleTask(
                        context,
                        task.title,
                        task.date,
                        task.time,
                        task.isCritical
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
