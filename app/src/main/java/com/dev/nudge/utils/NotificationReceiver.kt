package com.dev.nudge.utils

import android.Manifest
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.dev.nudge.R
import com.dev.nudge.data.RingtoneStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Fired by AlarmManager — even when the app process was killed, the system
 * starts a fresh process for this broadcast.
 *
 * Delivery order matters on a cold start:
 *  1. create the channel + post the reminder notification (cheap, reliable)
 *  2. only then try to ring for critical tasks (foreground-service start can
 *     be rejected on a background process — that must never lose the reminder)
 */
class NotificationReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NudgeAlarm"
    }

    override fun onReceive(context: Context, intent: Intent) {

        val title = intent.getStringExtra("title") ?: "Task Reminder"
        val isCritical = intent.getBooleanExtra("critical", false)

        Log.d(TAG, "Reminder received: '$title' critical=$isCritical")

        // 1️⃣ Reminder notification first — always
        NotificationHelper.createChannel(context)
        showNotification(context, title, isCritical)

        // 2️⃣ Ring afterwards for critical tasks (optional enhancement)
        if (!isCritical) return

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (RingtoneStorage.loadCriticalRing(context)) {
                    AlarmService.start(context, title, isCritical = true)
                    Log.d(TAG, "AlarmService started for critical reminder '$title'")
                }
            } catch (e: Exception) {
                // Never let the ring path lose the reminder notification
                Log.w(TAG, "Could not start AlarmService for '$title'", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun showNotification(context: Context, title: String, isCritical: Boolean) {
        try {
            val canPost = Build.VERSION.SDK_INT < 33 || ActivityCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!canPost) {
                Log.w(TAG, "POST_NOTIFICATIONS not granted — reminder '$title' not shown")
                return
            }

            // 🔴 critical tasks get their own wording with the task name
            val contentTitle = if (isCritical) "Critical Task" else "Nudge"
            val contentText =
                if (isCritical) "Time for $title to be done" else title

            val notification = NotificationCompat.Builder(context, "nudge_channel")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(contentTitle)
                .setContentText(contentText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .build()

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                    as NotificationManager
            manager.notify(System.currentTimeMillis().toInt(), notification)

            Log.d(TAG, "Reminder notification posted: '$title'")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to post reminder notification", e)
        }
    }
}
