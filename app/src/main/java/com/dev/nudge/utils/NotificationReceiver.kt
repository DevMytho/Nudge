package com.dev.nudge.utils

import android.Manifest
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.dev.nudge.R

class NotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {

        println("🔥 RECEIVER TRIGGERED")

        val title = intent.getStringExtra("title") ?: "Task Reminder"

        val notification = NotificationCompat.Builder(context, "nudge_channel")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Nudge")
            .setContentText(title)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(System.currentTimeMillis().toInt(), notification)
    }
}