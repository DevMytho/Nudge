package com.dev.nudge.screens

import android.app.Activity
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.dev.nudge.R
import com.dev.nudge.components.ProgressRing
import com.dev.nudge.data.TimerStatus
import com.dev.nudge.data.session.SegmentType
import com.dev.nudge.data.session.SessionMode
import com.dev.nudge.model.Task
import com.dev.nudge.model.TaskUtils
import com.dev.nudge.model.TimerEngine
import com.dev.nudge.model.TimerSessionSummary
import com.dev.nudge.model.TimerViewModel
import kotlinx.coroutines.delay
import java.util.Locale

private sealed interface LengthChoice {
    data class Minutes(val value: Int) : LengthChoice
    data object OpenEnded : LengthChoice
}

/**
 * ⏱ Focus tab (spec §6): task-based deep work on the shared TimerEngine.
 *
 * Idle → Start screen (task picker + length chips, default from settings —
 * never a hardcoded 25). Running → ring, ±5-only controls, optional break,
 * finish → summary card and the "Mark as done?" sheet for linked tasks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FocusScreen(
    tasks: List<Task>,
    timer: TimerViewModel,
    initialTaskId: String? = null,
    isFullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    onDone: (Task) -> Unit,
    onBack: () -> Unit
) {

    val context = LocalContext.current
    val activity = context as Activity

    val bgColor = MaterialTheme.colorScheme.background
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    val state by timer.state.collectAsState()
    val now by timer.now.collectAsState()
    val settings by timer.settings.collectAsState()

    val focusSecondsMap by timer.focusSecondsByTask.collectAsState()

    // 🔥 FULLSCREEN (existing behaviour)
    DisposableEffect(isFullscreen) {

        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)

        if (isFullscreen) {

            WindowCompat.setDecorFitsSystemWindows(window, false)

            window.statusBarColor = bgColor.toArgb()
            window.navigationBarColor = bgColor.toArgb()

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
        if (!settings.hapticsEnabled) return
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

    LaunchedEffect(Unit) {
        timer.messages.collect { msg ->
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    // ⏱ TICK — recompute remaining from the end timestamp every second;
    //    when the phase should have ended, reconcile through the engine.
    LaunchedEffect(state.status, state.endAtEpochMs) {
        if (state.status == TimerStatus.RUNNING) {
            while (true) {
                timer.tick()
                val left = timer.state.value.remainingAt(System.currentTimeMillis())
                if (left <= 0L) {
                    timer.refresh()
                    break
                }
                delay(left.coerceAtMost(1000L))
            }
        }
    }

    // 📋 SUMMARY / "MARK DONE?" SHEET
    var pendingSummary by remember { mutableStateOf<TimerSessionSummary?>(null) }
    LaunchedEffect(Unit) {
        timer.summary.collect { pendingSummary = it }
    }

    val summaryTask = pendingSummary?.taskId?.let { id -> tasks.find { it.id == id } }

    if (pendingSummary != null) {
        ModalBottomSheet(
            onDismissRequest = { pendingSummary = null },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            SessionSummaryContent(
                summary = pendingSummary!!,
                task = summaryTask,
                onDone = {
                    summaryTask?.let(onDone)
                    pendingSummary = null
                },
                onDismiss = { pendingSummary = null }
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(if (isFullscreen) 12.dp else 16.dp)
    ) {
        // 🚪 EXIT BUTTON (fullscreen only)
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
                        contentDescription = stringResource(R.string.focus_exit_fullscreen),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        if (!state.isActive()) {
            StartContent(
                tasks = tasks,
                settings = settings,
                initialTaskId = initialTaskId,
                focusSecondsByTask = focusSecondsMap,
                onStart = { task, minutes ->
                    vibrate()
                    timer.startSession(
                        mode = SessionMode.FOCUS,
                        taskId = task?.id,
                        taskTitle = task?.title ?: "",
                        lengthMinutes = minutes,
                        targetCycles = settings.cyclesBeforeLongBreak
                    )
                }
            )
        } else {
            RunningContent(
                state = state,
                now = now,
                isFullscreen = isFullscreen,
                onFullscreenChange = onFullscreenChange,
                onTogglePause = {
                    vibrate()
                    timer.togglePause()
                },
                onAdjust = { delta ->
                    vibrate()
                    timer.adjust(delta)
                },
                onFinish = {
                    vibrate()
                    timer.finishSession()
                },
                onTakeBreak = {
                    vibrate()
                    timer.takeBreak()
                },
                vibrate = ::vibrate
            )
        }
    }
}

// ---------------------------------------------------------------- start UI

@Composable
private fun StartContent(
    tasks: List<Task>,
    settings: com.dev.nudge.data.UserSettings,
    initialTaskId: String?,
    focusSecondsByTask: Map<String, Int>,
    onStart: (Task?, Int?) -> Unit
) {

    val todayIso = TaskUtils.todayIso()
    val todayTasks = remember(tasks) {
        TaskUtils.sortTasks(tasks.filter { it.date == todayIso && !it.isCompleted })
    }

    var selectedTaskId by remember { mutableStateOf(initialTaskId) }
    val selectedTask = todayTasks.find { it.id == selectedTaskId }

    // Length selection: null = the user's default (settings.focusMinutes)
    var customMode by remember { mutableStateOf(false) }
    var customMinutes by remember { mutableStateOf(settings.focusMinutes.coerceIn(5, 180)) }
    var openEnded by remember { mutableStateOf(false) }
    var chosenMinutes by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(settings.focusMinutes) {
        // keep the stepper sane when settings change under us
        customMinutes = customMinutes.coerceIn(5, 180)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            stringResource(R.string.focus_start_title),
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(24.dp))

        // 🎯 TASK PICKER (today's incomplete tasks + free focus)
        Text(
            stringResource(R.string.focus_pick_task),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedTaskId == null,
                onClick = { selectedTaskId = null },
                label = { Text(stringResource(R.string.focus_no_task)) }
            )

            todayTasks.forEach { task ->
                FilterChip(
                    selected = selectedTaskId == task.id,
                    onClick = { selectedTaskId = task.id },
                    label = {
                        Text(
                            text = task.title,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                )
            }
        }

        if (todayTasks.isEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                stringResource(R.string.focus_no_tasks),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ⏱ LENGTH CHIPS
        Text(
            stringResource(R.string.focus_length),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        )

        // remaining estimate of the selected task (min 5 min)
        val estimateMinutes = selectedTask?.let { t ->
            val est = TaskUtils.estimatedMinutesEffective(t)
            if (est == null) null
            else {
                val focusedMin = (focusSecondsByTask[t.id] ?: 0) / 60
                (est - focusedMin).coerceAtLeast(5)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (estimateMinutes != null) {
                FilterChip(
                    selected = !openEnded && !customMode && chosenMinutes == estimateMinutes,
                    onClick = {
                        openEnded = false
                        customMode = false
                        chosenMinutes = estimateMinutes
                    },
                    label = {
                        Text(
                            stringResource(
                                R.string.focus_length_estimate,
                                TaskUtils.formatMinutes(estimateMinutes)
                            )
                        )
                    }
                )
            }

            val presets = remember(settings.focusMinutes) {
                listOf(15, settings.focusMinutes, 45, 60).distinct().sorted()
            }
            presets.forEach { minutes ->
                FilterChip(
                    selected = !openEnded && !customMode && chosenMinutes == minutes,
                    onClick = {
                        openEnded = false
                        customMode = false
                        chosenMinutes = minutes
                    },
                    label = { Text(stringResource(R.string.focus_length_minutes, minutes)) }
                )
            }

            FilterChip(
                selected = customMode,
                onClick = {
                    customMode = true
                    openEnded = false
                },
                label = { Text(stringResource(R.string.focus_length_custom)) }
            )

            FilterChip(
                selected = openEnded,
                onClick = {
                    openEnded = true
                    customMode = false
                },
                label = { Text(stringResource(R.string.focus_open_ended)) }
            )
        }

        if (customMode && !openEnded) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { customMinutes = (customMinutes - 5).coerceAtLeast(5) },
                    enabled = customMinutes > 5
                ) { Text("−") }

                Text(
                    text = stringResource(R.string.focus_length_minutes, customMinutes),
                    style = MaterialTheme.typography.titleMedium
                )

                OutlinedButton(
                    onClick = { customMinutes = (customMinutes + 5).coerceAtMost(180) },
                    enabled = customMinutes < 180
                ) { Text("+") }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // 🚀 START
        Button(
            onClick = {
                val minutes = when {
                    openEnded -> null
                    customMode -> customMinutes
                    chosenMinutes != null -> chosenMinutes
                    else -> settings.focusMinutes // 🔥 default chip = the setting
                }
                onStart(selectedTask, minutes)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Text(stringResource(R.string.focus_start))
        }
    }
}

// -------------------------------------------------------------- running UI

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RunningContent(
    state: com.dev.nudge.data.TimerState,
    now: Long,
    isFullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    onTogglePause: () -> Unit,
    onAdjust: (Int) -> Boolean,
    onFinish: () -> Unit,
    onTakeBreak: () -> Unit,
    vibrate: () -> Unit
) {

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {

        val timeLeft = state.remainingAt(now)
        val isOpenEnded = state.plannedSecCurrentSegment <= 0

        val timeText = if (isOpenEnded) {
            val elapsedMs = state.elapsedMsAt(now)
            String.format(
                Locale.US,
                "%02d:%02d",
                elapsedMs / 60_000L,
                (elapsedMs % 60_000L) / 1000L
            )
        } else {
            String.format(
                Locale.US,
                "%02d:%02d",
                timeLeft / 60_000L,
                (timeLeft % 60_000L) / 1000L
            )
        }

        val baseTime = (state.plannedSecCurrentSegment * 1000L).coerceAtLeast(1L)
        val progress = if (isOpenEnded) {
            0f
        } else {
            (1f - (timeLeft.toFloat() / baseTime)).coerceIn(0f, 1f)
        }

        // 🖱 TAP RING → FULLSCREEN + HAPTIC
        Box(
            modifier = Modifier.clickable {
                vibrate()
                onFullscreenChange(!isFullscreen)
            }
        ) {
            ProgressRing(progress = progress, timeText = timeText)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 🏷 PHASE + STATUS
        if (!isFullscreen) {
            val phaseLabel = when (state.phase) {
                SegmentType.FOCUS -> stringResource(R.string.phase_focus)
                SegmentType.SHORT_BREAK -> stringResource(R.string.phase_short_break)
                SegmentType.LONG_BREAK -> stringResource(R.string.phase_long_break)
                null -> ""
            }
            Text(
                text = "$phaseLabel · ${
                    if (state.status == TimerStatus.RUNNING) {
                        stringResource(R.string.session_running)
                    } else {
                        stringResource(R.string.session_paused)
                    }
                }",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (state.taskId != null && state.taskTitleSnapshot.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = state.taskTitleSnapshot,
                    style = MaterialTheme.typography.headlineSmall
                )
            } else if (state.taskId == null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.focus_no_task),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
        }

        // 🎛 CONTROLS
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onTogglePause, shape = RoundedCornerShape(20.dp)) {
                Text(
                    if (state.status == TimerStatus.RUNNING) {
                        stringResource(R.string.focus_pause)
                    } else {
                        stringResource(R.string.focus_resume)
                    }
                )
            }

            Button(onClick = onFinish) {
                Text(stringResource(R.string.focus_finish))
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // ⏱ ±5 ONLY (disabled when it would drain remaining or in stopwatch mode)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val remainingMs = state.remainingAt(now)
            OutlinedButton(
                onClick = { onAdjust(-TimerEngine.ADJUST_SEC) },
                enabled = !isOpenEnded &&
                    remainingMs > TimerEngine.ADJUST_SEC * 1000L
            ) {
                Text(stringResource(R.string.focus_adjust_minus))
            }

            OutlinedButton(
                onClick = { onAdjust(TimerEngine.ADJUST_SEC) },
                enabled = !isOpenEnded
            ) {
                Text(stringResource(R.string.focus_adjust_plus))
            }
        }

        // 🧘 optional break (Focus mode only)
        if (state.mode == SessionMode.FOCUS &&
            !(state.phase == SegmentType.SHORT_BREAK)
        ) {
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedButton(onClick = onTakeBreak) {
                Text(stringResource(R.string.focus_take_break))
            }
        }
    }
}

// --------------------------------------------------------------- summary UI

@Composable
private fun SessionSummaryContent(
    summary: TimerSessionSummary,
    task: Task?,
    onDone: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            stringResource(R.string.summary_title),
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            stringResource(
                R.string.summary_focus_time,
                TaskUtils.formatMinutes(summary.focusSec / 60)
            )
        )
        Text(
            stringResource(
                R.string.summary_break_time,
                TaskUtils.formatMinutes(summary.breakSec / 60)
            )
        )
        Text(
            stringResource(
                R.string.summary_extensions,
                TaskUtils.formatMinutes(summary.extensionSec / 60)
            )
        )
        if (summary.cycles > 0) {
            Text(stringResource(R.string.summary_cycles, summary.cycles))
        }

        Spacer(modifier = Modifier.height(20.dp))

        val linkedTask = task
        if (linkedTask != null && !linkedTask.isCompleted) {
            Text(
                stringResource(R.string.done_sheet_title, linkedTask.title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onDone) {
                    Text(stringResource(R.string.done_sheet_yes))
                }
                OutlinedButton(onClick = onDismiss) {
                    Text(stringResource(R.string.done_sheet_not_yet))
                }
            }
        } else {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.summary_close))
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}
