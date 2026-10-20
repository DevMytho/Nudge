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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dev.nudge.R
import com.dev.nudge.data.RingtoneStorage
import com.dev.nudge.data.TimerState
import com.dev.nudge.data.TimerStorage
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

        /** Starts ringing (timer finished / critical reminder). */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AlarmService::class.java)
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
        when (intent?.action) {
            ACTION_STOP -> {
                stopRinging()
                // 🧹 back to a fresh timer
                serviceScope.launch { TimerStorage.saveState(this@AlarmService, TimerState()) }
                finishService()
                return START_NOT_STICKY
            }

            ACTION_SNOOZE -> {
                stopRinging()
                val endAt = System.currentTimeMillis() + TimerAlarmScheduler.SNOOZE_MILLIS
                if (!TimerAlarmScheduler.schedule(this, endAt)) {
                    // 🔁 fall back to an inexact alarm so snooze still rings
                    scheduleInexact(endAt)
                }
                // keep the UI consistent: 5 more minutes, running
                serviceScope.launch {
                    TimerStorage.saveState(
                        this@AlarmService,
                        TimerState(
                            status = com.dev.nudge.data.TimerStatus.RUNNING,
                            endAtMillis = endAt,
                            remainingMillis = TimerAlarmScheduler.SNOOZE_MILLIS
                        )
                    )
                }
                finishService()
                return START_NOT_STICKY
            }

            else -> startRinging()
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

    private fun startRinging() {
        if (ringing) return
        ringing = true

        // 🔔 must be foreground ASAP after startForegroundService()
        showOngoingNotification()
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

    private fun showOngoingNotification() {
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

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Time's up!")
            .setContentText("Take a break 🎉")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "Stop", stopIntent)
            .addAction(0, "Snooze +5 min", snoozeIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIF_ID, notification)
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
