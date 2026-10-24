package com.dev.nudge.utils

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dev.nudge.R
import com.dev.nudge.data.RingtoneStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 🔔 Foreground service that rings the chosen timer ringtone when the
 * Pomodoro timer (or a critical task reminder) fires.
 *
 * - Ongoing notification with Stop and Snooze +5 min actions
 * - MediaPlayer looping with USAGE_ALARM audio attributes
 * - Vibration pattern + audio focus
 * - Auto-stops after [AUTO_STOP_MILLIS] so it never rings forever
 * - Uses its own SILENT notification channel (a channel's sound can't be
 *   changed after creation, so the sound comes from MediaPlayer instead)
 */
class AlarmService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var ringing = false

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private val autoStopRunnable = Runnable { stopSelf() }

    companion object {
        const val ACTION_STOP = "com.dev.nudge.action.ALARM_STOP"
        const val ACTION_SNOOZE = "com.dev.nudge.action.ALARM_SNOOZE"

        private const val CHANNEL_ID = "nudge_alarm_service"
        private const val NOTIF_ID = 55001
        private const val AUTO_STOP_MILLIS = 60_000L
        private const val TAG = "NudgeAlarm"

        private const val EXTRA_TASK_TITLE = "task_title"
        private const val EXTRA_IS_CRITICAL = "is_critical"
        private const val EXTRA_TIMER_MESSAGE = "timer_message"

        /**
         * Starts ringing.
         *
         * @param taskTitle when non-null (critical task reminder) the ongoing
         *   notification says "Time for {task} to be done" instead of the
         *   timer message.
         * @param timerMessage wording for phase-end rings (e.g. break started,
         *   break over, session complete).
         */
        fun start(
            context: Context,
            taskTitle: String? = null,
            isCritical: Boolean = false,
            timerMessage: String? = null
        ) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AlarmService::class.java).apply {
                    if (taskTitle != null) putExtra(EXTRA_TASK_TITLE, taskTitle)
                    putExtra(EXTRA_IS_CRITICAL, isCritical)
                    if (timerMessage != null) putExtra(EXTRA_TIMER_MESSAGE, timerMessage)
                }
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        audioManager = getSystemService(AudioManager::class.java)
        vibrator = getSystemService(Vibrator::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand action=${intent?.action ?: "(ring)"}")

        when (intent?.action) {
            ACTION_STOP -> {
                // Engine state was already reconciled when the alarm fired —
                // Stop only silences the ring (next phase waits for a tap).
                stopRinging()
                finishService()
                return START_NOT_STICKY
            }

            ACTION_SNOOZE -> {
                stopRinging()
                // 🔁 ring again in 5 minutes; timer state is left alone
                val endAt = System.currentTimeMillis() + TimerAlarmScheduler.SNOOZE_MILLIS
                if (!TimerAlarmScheduler.schedule(this, endAt)) {
                    scheduleInexact(endAt)
                }
                finishService()
                return START_NOT_STICKY
            }

            else -> startRinging(
                taskTitle = intent?.getStringExtra(EXTRA_TASK_TITLE),
                isCritical = intent?.getBooleanExtra(EXTRA_IS_CRITICAL, false) ?: false,
                timerMessage = intent?.getStringExtra(EXTRA_TIMER_MESSAGE)
            )
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopRinging()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------- ring

    private fun startRinging(
        taskTitle: String? = null,
        isCritical: Boolean = false,
        timerMessage: String? = null
    ) {
        if (ringing) return

        // 🔔 must be foreground ASAP after startForegroundService().
        //    If the system rejects the FGS start (background start restrictions),
        //    bail out cleanly instead of crashing the cold-started process.
        if (!showOngoingNotification(taskTitle, isCritical, timerMessage)) {
            Log.w(TAG, "Could not enter foreground — stopping alarm service")
            stopSelf()
            return
        }

        ringing = true

        requestAudioFocus()
        startVibrating()

        serviceScope.launch {
            try {
                val uri = RingtoneStorage.resolveRingtoneUri(this@AlarmService)
                val player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    setDataSource(this@AlarmService, uri)
                    isLooping = true
                    prepare()
                    start()
                }
                mediaPlayer = player
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 🛟 never ring forever
        handler.removeCallbacks(autoStopRunnable)
        handler.postDelayed(autoStopRunnable, AUTO_STOP_MILLIS)
    }

    private fun stopRinging() {
        ringing = false
        handler.removeCallbacks(autoStopRunnable)

        mediaPlayer?.let { player ->
            try {
                if (player.isPlaying) player.stop()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            player.release()
        }
        mediaPlayer = null

        try {
            vibrator?.cancel()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        abandonAudioFocus()
    }

    private fun finishService() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ------------------------------------------------------------ vibration

    private fun startVibrating() {
        val v = vibrator ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(
                    VibrationEffect.createWaveform(
                        longArrayOf(0, 600, 600),
                        intArrayOf(0, 255, 0),
                        0
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(longArrayOf(0, 600, 600), 0)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ---------------------------------------------------------- audio focus

    @Suppress("DEPRECATION")
    private fun requestAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setWillPauseWhenDucked(false)
                .build()
            audioFocusRequest = request
            am.requestAudioFocus(request)
        } else {
            am.requestAudioFocus(
                null,
                AudioManager.STREAM_ALARM,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            am.abandonAudioFocus(null)
        }
    }

    // --------------------------------------------------------- notification

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Timer alarm",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                // 🔇 silent channel — the ringtone comes from MediaPlayer
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun showOngoingNotification(
        taskTitle: String?,
        isCritical: Boolean,
        timerMessage: String? = null
    ): Boolean {
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, AlarmService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val snoozeIntent = PendingIntent.getService(
            this, 2,
            Intent(this, AlarmService::class.java).setAction(ACTION_SNOOZE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 🔴 critical task reminder → name the task; timer end → the phase message
        val contentTitle: String
        val contentText: String
        if (isCritical && !taskTitle.isNullOrBlank()) {
            contentTitle = "Critical Task"
            contentText = "Time for $taskTitle to be done"
        } else {
            contentTitle = "Time's up!"
            contentText = timerMessage ?: "Take a break 🎉"
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "Stop", stopIntent)
            .addAction(0, "Snooze +5 min", snoozeIntent)
            .build()

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIF_ID, notification)
            }
            true
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException on Android 12+
            Log.w(TAG, "startForeground failed", e)
            false
        }
    }

    // -------------------------------------------------------------- helpers

    private fun scheduleInexact(endAtMillis: Long) {
        try {
            val intent = Intent(this, TimerAlarmReceiver::class.java).apply {
                action = TimerAlarmScheduler.ACTION_TIMER_FINISH
            }
            val pendingIntent = PendingIntent.getBroadcast(
                this,
                TimerAlarmScheduler.REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                endAtMillis,
                pendingIntent
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
