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
import com.dev.nudge.data.RingtoneStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {

        val title = intent.getStringExtra("title") ?: "Task Reminder"
        val isCritical = intent.getBooleanExtra("critical", false)

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 🔔 Optionally ring (instead of a plain notification) for
                //    critical tasks — see the Settings toggle.
                val shouldRing =
                    isCritical && RingtoneStorage.loadCriticalRing(context)

                if (shouldRing) {
                    AlarmService.start(context)
                } else {
                    showNotification(context, title)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun showNotification(context: Context, title: String) {
        val canPost = ActivityCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

        if (!canPost && android.os.Build.VERSION.SDK_INT >= 33) return

        val notification = NotificationCompat.Builder(context, "nudge_channel")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Nudge")
            .setContentText(title)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(System.currentTimeMillis().toInt(), notification)
    }
}
