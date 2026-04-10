package com.dev.nudge.screens

import android.app.Activity
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import androidx.annotation.RequiresApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dev.nudge.components.ProgressRing
import com.dev.nudge.model.Task
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.graphics.toArgb

@Composable
fun FocusScreen(
    tasks: List<Task>,
    onDone: (Task) -> Unit,
    onBack: () -> Unit
) {

    val context = LocalContext.current
    val activity = context as Activity

    var isFullscreen by remember { mutableStateOf(false) }

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
    @RequiresApi(Build.VERSION_CODES.O)
    fun vibrate() {
        val vibrator = context.getSystemService(Vibrator::class.java)
        vibrator?.vibrate(
            VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE)
        )
    }

    fun getPriorityValue(task: Task): Int {
        return when {
            task.isCritical -> 0
            task.priority == "High" -> 1
            task.priority == "Medium" -> 2
            else -> 3
        }
    }

    fun getDeadlineValue(task: Task): Long {
        return try {
            val sdf = SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault())
            sdf.parse("${task.date} ${task.time}")?.time ?: Long.MAX_VALUE
        } catch (e: Exception) {
            Long.MAX_VALUE
        }
    }

    val sortedTasks = tasks.sortedWith(
        compareBy<Task>(
            { getPriorityValue(it) },
            { getDeadlineValue(it) }
        )
    )

    var currentTask by remember { mutableStateOf<Task?>(null) }
    var timeLeft by rememberSaveable { mutableStateOf(1500) }
    var isRunning by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(tasks) {
        currentTask = sortedTasks.firstOrNull()
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
                    isFullscreen = false
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

            val minutes = timeLeft / 60
            val seconds = timeLeft % 60
            val baseTime = 1500f
            val progress = (1f - (timeLeft / baseTime)).coerceIn(0f, 1f)

            // 🔥 TAP → FULLSCREEN + HAPTIC
            Box(
                modifier = Modifier.clickable {
                    vibrate()
                    isFullscreen = true
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
                    currentTask!!.time,
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
                        isRunning = !isRunning
                    },
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text(if (isRunning) "Pause" else "Start")
                }

                Button(
                    onClick = {
                        timeLeft = 1500
                        isRunning = false
                    }
                ) {
                    Text("Reset")
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {

                Button(onClick = { timeLeft += 300 }) {
                    Text("+5 min")
                }

                Button(onClick = { timeLeft += 600 }) {
                    Text("+10 min")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    val finished = currentTask!!
                    onDone(finished)

                    val remaining = tasks.filter { it != finished }

                    currentTask = remaining
                        .sortedWith(
                            compareBy<Task>(
                                { getPriorityValue(it) },
                                { getDeadlineValue(it) }
                            )
                        )
                        .firstOrNull()

                    timeLeft = 1500
                    isRunning = false
                }
            ) {
                Text("Mark as Done")
            }
        }
    }

    // ⏱ TIMER LOOP
    LaunchedEffect(isRunning) {
        while (isRunning && timeLeft > 0) {
            delay(1000)
            timeLeft--
        }
    }
}