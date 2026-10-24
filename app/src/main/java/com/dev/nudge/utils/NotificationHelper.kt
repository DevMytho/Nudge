package com.dev.nudge.utils

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.dev.nudge.MainActivity
import com.dev.nudge.R

object NotificationHelper {

    private const val CHANNEL_ID = "nudge_channel"
    private const val SESSION_NOTIF_ID = 55002

    fun createChannel(context: Context) {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel = NotificationChannel(
                "nudge_channel",
                "Nudge Notifications",
                NotificationManager.IMPORTANCE_HIGH
            )

            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * 🔔 Persistent ongoing notification while a focus/pomodoro session runs
     * (spec §10): shows the current phase and remaining time; tapping opens
     * the app. Optional Pause/±5 actions are not implemented — see
     * docs/DECISIONS.md.
     */
    fun showSessionNotification(context: Context, phaseLabel: String, timeText: String) {
        val canPost = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!canPost) return

        val openIntent = PendingIntent.getActivity(
            context,
            77002,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(phaseLabel)
            .setContentText(timeText)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()

        NotificationManagerCompat.from(context).notify(SESSION_NOTIF_ID, notification)
    }

    fun cancelSessionNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(SESSION_NOTIF_ID)
    }
}
