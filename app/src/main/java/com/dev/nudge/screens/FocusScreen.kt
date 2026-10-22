package com.dev.nudge.screens

import android.app.Activity
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.dev.nudge.components.ProgressRing
import com.dev.nudge.data.TimerState
import com.dev.nudge.data.TimerStatus
import com.dev.nudge.model.Task
import com.dev.nudge.model.TaskUtils
import com.dev.nudge.model.TimerViewModel
import kotlinx.coroutines.delay

@Composable
fun FocusScreen(
    tasks: List<Task>,
    timer: TimerViewModel,
    initialTaskId: String? = null, // 🎯 preselected by the Tasks tab ▶ Start
    isFullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    onDone: (Task) -> Unit,
    onBack: () -> Unit
) {

    val context = LocalContext.current
    val activity = context as Activity

    val bgColor = MaterialTheme.colorScheme.background
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    // 🔥 FULLSCREEN
    DisposableEffect(isFullscreen) {

        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)


        if (isFullscreen) {

            WindowCompat.setDecorFitsSystemWindows(window, false)

            // 🎨 MATCH APP THEME
            window.statusBarColor = bgColor.toArgb()
            window.navigationBarColor = bgColor.toArgb()

            // 🔥 IMPORTANT: disable light icons if dark bg

            controller?.isAppearanceLightStatusBars = !isDark
            controller?.isAppearanceLightNavigationBars = !isDark

            controller?.hide(WindowInsetsCompat.Type.systemBars())

            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        } else {

            WindowCompat.setDecorFitsSystemWindows(window, true)

            window.statusBarColor = bgColor.toArgb()
            window.navigationBarColor = bgColor.toArgb()

            controller?.show(WindowInsetsCompat.Type.systemBars())
        }

        onDispose {
            WindowCompat.setDecorFitsSystemWindows(window, true)
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // 📳 HAPTIC
    fun vibrate() {
        val vibrator = context.getSystemService(Vibrator::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(
                VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(40)
        }
    }

    fun getPriorityValue(task: Task): Int = TaskUtils.priorityValue(task).let {
        if (task.critical) 0 else it
    }

    fun sortTasks(list: List<Task>): List<Task> = list.sortedWith(
        compareBy<Task>(
            { getPriorityValue(it) },
            {
                try {
                    java.text.SimpleDateFormat("dd-MM-yyyy HH:mm", java.util.Locale.getDefault())
                        .parse("${it.date} ${it.time}")?.time ?: Long.MAX_VALUE
                } catch (e: Exception) {
                    Long.MAX_VALUE
                }
            }
        )
    )

    var currentTask by remember { mutableStateOf<Task?>(null) }

    LaunchedEffect(tasks, initialTaskId) {
        currentTask = tasks.find { it.id == initialTaskId }
            ?: sortTasks(tasks).firstOrNull()
    }

    // ⏱ TIMER STATE from the ViewModel (end-timestamp model)
    val state by timer.state.collectAsState()
    val now by timer.now.collectAsState()
    val timeLeft = state.remainingAt(now)

    // ⏱ TICK — recompute remaining time from the end timestamp every second
    LaunchedEffect(state.status, state.endAtMillis) {
        if (state.status == TimerStatus.RUNNING) {
            while (true) {
                timer.tick()
                val left = timer.state.value.remainingAt(System.currentTimeMillis())
                if (left <= 0L) break
                delay(left.coerceAtMost(1000L))
            }
        }
    }

    LaunchedEffect(Unit) {
        timer.messages.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(if (isFullscreen) 12.dp else 16.dp)
    ) {
        // 🚪 EXIT BUTTON
        if (isFullscreen) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = {
                    vibrate()
                    onFullscreenChange(false)
                }) {
                    Icon(
                        Icons.Default.Logout,
                        contentDescription = "Exit",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {

            if (currentTask == null) {
                Text("No tasks left 🎉")
                return
            }

            val minutes = timeLeft / 60_000
            val seconds = (timeLeft % 60_000) / 1000
            val baseTime = state.totalMillis.toFloat()
            val progress = (1f - (timeLeft / baseTime)).coerceIn(0f, 1f)

            // 🔥 TAP → FULLSCREEN + HAPTIC
            Box(
                modifier = Modifier.clickable {
                    vibrate()
                    onFullscreenChange(true)
                }
            ) {
                ProgressRing(
                    progress = progress,
                    timeText = String.format("%02d:%02d", minutes, seconds)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 🧠 TASK INFO (hidden in fullscreen for focus)
            if (!isFullscreen) {
                Text(
                    "Now focusing on",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    currentTask!!.title,
                    style = MaterialTheme.typography.headlineSmall
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    currentTask!!.dueTime ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(20.dp))
            }

            // 🎛 CONTROLS (always visible)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {

                Button(
                    onClick = {
                        vibrate()
                        if (state.status == TimerStatus.RUNNING) {
                            timer.pause()
                        } else {
                            timer.start(currentTask?.id ?: "")
                        }
                    },
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text(if (state.status == TimerStatus.RUNNING) "Pause" else "Start")
                }

                Button(
                    onClick = {
                        vibrate()
                        timer.reset()
                    }
                ) {
                    Text("Reset")
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { timer.addTime(300_000L) }) {
                    Text("+5 min")
                }

                Button(onClick = { timer.addTime(600_000L) }) {
                    Text("+10 min")
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { timer.addTime(-300_000L) }) {
                    Text("-5 min")
                }

                Button(onClick = { timer.addTime(-600_000L) }) {
                    Text("-10 min")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    val finished = currentTask!!
                    onDone(finished)

                    val remaining = tasks.filter { it != finished }
                    currentTask = sortTasks(remaining).firstOrNull()

                    timer.reset()
                }
            ) {
                Text("Mark as Done")
            }
        }
    }
}
