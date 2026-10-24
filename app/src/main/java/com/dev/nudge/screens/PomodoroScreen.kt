package com.dev.nudge.screens

import android.app.Activity
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dev.nudge.R
import com.dev.nudge.components.ProgressRing
import com.dev.nudge.components.SessionElsewhere
import com.dev.nudge.data.TimerState
import com.dev.nudge.data.TimerStatus
import com.dev.nudge.data.UserSettings
import com.dev.nudge.data.session.SegmentType
import com.dev.nudge.data.session.SessionMode
import com.dev.nudge.data.session.SessionSegmentEntity
import com.dev.nudge.model.Task
import com.dev.nudge.model.TaskUtils
import com.dev.nudge.model.TimerEngine
import com.dev.nudge.model.TimerSessionSummary
import com.dev.nudge.model.TimerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import java.util.Locale

private val BreakGreen = Color(0xFF66BB6A)

/**
 * 🍅 Pomodoro tab (spec §7): cycle-structured timer on the shared
 * [TimerEngine] — focus → short break → … → long break after N blocks.
 *
 * Start screen: optional task link + session-only length overrides and a
 * target number of cycles (defaults to the task's estimatedCycles, else the
 * user's cyclesBeforeLongBreak). Running screen: cycle x of y, cycle dots,
 * phase-coloured label, ±5 / Pause / Skip / Finish and a collapsible live
 * session log with every segment's exact duration.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PomodoroScreen(
    tasks: List<Task>,
    timer: TimerViewModel,
    initialTaskId: String? = null,
    onDone: (Task) -> Unit,
    onGoToFocus: () -> Unit
) {

    val context = LocalContext.current
    val state by timer.state.collectAsState()
    val now by timer.now.collectAsState()
    val settings by timer.settings.collectAsState()

    fun vibrate() {
        if (!settings.hapticsEnabled) return
        val vibrator = context.getSystemService(Vibrator::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
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

    // ⏱ tick every second while running; reconcile when the phase is overdue
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

    // 📋 summary / "Mark as done?" sheet (shared with the Focus tab)
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
            .padding(16.dp)
    ) {
        when {
            // 🔒 a Focus session owns the timer — offer a jump instead
            state.isActive() && state.mode == SessionMode.FOCUS -> {
                SessionElsewhere(
                    tabLabel = stringResource(R.string.focus_start_title),
                    onGoTo = onGoToFocus
                )
            }

            !state.isActive() -> {
                PomodoroStart(
                    tasks = tasks,
                    settings = settings,
                    initialTaskId = initialTaskId,
                    onStart = { task, focusMin, targetCycles, overrides ->
                        vibrate()
                        timer.startSession(
                            mode = SessionMode.POMODORO,
                            taskId = task?.id,
                            taskTitle = task?.title ?: "",
                            lengthMinutes = focusMin,
                            targetCycles = targetCycles,
                            overrides = overrides
                        )
                    }
                )
            }

            else -> {
                PomodoroRunning(
                    state = state,
                    now = now,
                    segmentsFlow = timer.segmentsForSession(state.sessionId),
                    onTogglePause = { vibrate(); timer.togglePause() },
                    onAdjust = { delta -> vibrate(); timer.adjust(delta) },
                    onSkip = { vibrate(); timer.skipPhase() },
                    onFinish = { vibrate(); timer.finishSession() },
                    vibrate = ::vibrate
                )
            }
        }
    }
}

// ---------------------------------------------------------------- start UI

@Composable
private fun PomodoroStart(
    tasks: List<Task>,
    settings: UserSettings,
    initialTaskId: String?,
    onStart: (Task?, Int, Int, UserSettings) -> Unit
) {

    val todayIso = TaskUtils.todayIso()
    val todayTasks = remember(tasks) {
        TaskUtils.sortTasks(tasks.filter { it.date == todayIso && !it.isCompleted })
    }

    var selectedTaskId by remember { mutableStateOf(initialTaskId) }
    val selectedTask = todayTasks.find { it.id == selectedTaskId }

    // Session-only overrides start from the user's settings.
    var focusMin by remember { mutableStateOf(settings.focusMinutes) }
    var shortMin by remember { mutableStateOf(settings.shortBreakMinutes) }
    var longMin by remember { mutableStateOf(settings.longBreakMinutes) }
    var blocksBeforeLong by remember { mutableStateOf(settings.cyclesBeforeLongBreak) }

    // Target cycles: the task's estimate if it has one, else the setting.
    var targetCycles by remember {
        mutableStateOf(
            todayTasks.find { it.id == initialTaskId }?.estimatedCycles
                ?: settings.cyclesBeforeLongBreak
        )
    }
    LaunchedEffect(selectedTaskId) {
        targetCycles = selectedTask?.estimatedCycles
            ?: settings.cyclesBeforeLongBreak
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            stringResource(R.string.pomodoro_title),
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(24.dp))

        // 🎯 TASK PICKER
        Text(
            stringResource(R.string.pomodoro_task),
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

        Spacer(modifier = Modifier.height(24.dp))

        // ⚙️ SESSION-ONLY LENGTH OVERRIDES
        Text(
            stringResource(R.string.pomodoro_lengths),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp)
        )

        StepperRow(
            label = stringResource(R.string.pomodoro_focus_min),
            valueText = stringResource(R.string.focus_length_minutes, focusMin),
            value = focusMin, min = 5, max = 180,
            onChange = { focusMin = it }
        )
        StepperRow(
            label = stringResource(R.string.pomodoro_short_break_min),
            valueText = stringResource(R.string.focus_length_minutes, shortMin),
            value = shortMin, min = 1, max = 60,
            onChange = { shortMin = it }
        )
        StepperRow(
            label = stringResource(R.string.pomodoro_long_break_min),
            valueText = stringResource(R.string.focus_length_minutes, longMin),
            value = longMin, min = 1, max = 60,
            onChange = { longMin = it }
        )
        StepperRow(
            label = stringResource(R.string.pomodoro_blocks_before_long),
            valueText = stringResource(R.string.pomodoro_target_cycles_unit, blocksBeforeLong),
            value = blocksBeforeLong, min = 2, max = 10,
            onChange = { blocksBeforeLong = it }
        )
        StepperRow(
            label = stringResource(R.string.pomodoro_target_cycles),
            valueText = stringResource(R.string.pomodoro_target_cycles_unit, targetCycles),
            value = targetCycles, min = 1, max = 12,
            onChange = { targetCycles = it }
        )

        Text(
            stringResource(R.string.pomodoro_overrides_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 24.dp)
        )

        // 🚀 START
        Button(
            onClick = {
                val overrides = settings.copy(
                    focusMinutes = focusMin,
                    shortBreakMinutes = shortMin,
                    longBreakMinutes = longMin,
                    cyclesBeforeLongBreak = blocksBeforeLong
                )
                onStart(selectedTask, focusMin, targetCycles, overrides)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Text(stringResource(R.string.focus_start))
        }
    }
}

@Composable
private fun StepperRow(
    label: String,
    valueText: String,
    value: Int,
    min: Int,
    max: Int,
    onChange: (Int) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        OutlinedButton(
            onClick = { onChange((value - 1).coerceAtLeast(min)) },
            enabled = value > min,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
        ) { Text("−") }
        Text(
            valueText,
            modifier = Modifier.padding(horizontal = 12.dp),
            style = MaterialTheme.typography.titleSmall
        )
        OutlinedButton(
            onClick = { onChange((value + 1).coerceAtMost(max)) },
            enabled = value < max,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
        ) { Text("+") }
    }
}

// -------------------------------------------------------------- running UI

@Composable
private fun PomodoroRunning(
    state: TimerState,
    now: Long,
    segmentsFlow: Flow<List<SessionSegmentEntity>>,
    onTogglePause: () -> Unit,
    onAdjust: (Int) -> Boolean,
    onSkip: () -> Unit,
    onFinish: () -> Unit,
    vibrate: () -> Unit
) {

    val segments by segmentsFlow.collectAsState(initial = emptyList())
    var logOpen by remember { mutableStateOf(false) }

    val timeLeft = state.remainingAt(now)
    val plannedMs = (state.plannedSecCurrentSegment * 1000L).coerceAtLeast(1L)
    val progress = (1f - (timeLeft.toFloat() / plannedMs)).coerceIn(0f, 1f)

    val phase = state.phase
    val isFocusPhase = phase == SegmentType.FOCUS
    val phaseColor = if (isFocusPhase) MaterialTheme.colorScheme.primary else BreakGreen

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {

        ProgressRing(
            progress = progress,
            timeText = formatClock(timeLeft)
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 🏷 PHASE + STATUS (colour distinguishes focus vs break)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = phaseColor,
                shape = RoundedCornerShape(50),
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                Text(
                    text = phaseLabel(phase) + " · " + if (state.status == TimerStatus.RUNNING) {
                        stringResource(R.string.session_running)
                    } else {
                        stringResource(R.string.session_paused)
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 🔢 CYCLE x OF y + DOTS
        Text(
            stringResource(R.string.pomodoro_cycle_of, state.cycleIndex, state.targetCycles),
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        val completedCycles = (if (isFocusPhase) state.cycleIndex - 1 else state.cycleIndex)
            .coerceIn(0, state.targetCycles)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(state.targetCycles) { i ->
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(if (i < completedCycles) phaseColor else MaterialTheme.colorScheme.surfaceVariant)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (state.taskId != null && state.taskTitleSnapshot.isNotBlank()) {
            Text(
                text = state.taskTitleSnapshot,
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(modifier = Modifier.height(14.dp))
        } else {
            Spacer(modifier = Modifier.height(6.dp))
        }

        // 🎛 CONTROLS: Pause/Resume + Finish
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

        // ⏱ ±5 (works in focus AND break phases)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { onAdjust(-TimerEngine.ADJUST_SEC) },
                enabled = state.plannedSecCurrentSegment > 0 &&
                    timeLeft > TimerEngine.ADJUST_SEC * 1000L
            ) {
                Text(stringResource(R.string.focus_adjust_minus))
            }
            OutlinedButton(
                onClick = { onAdjust(TimerEngine.ADJUST_SEC) },
                enabled = state.plannedSecCurrentSegment > 0
            ) {
                Text(stringResource(R.string.focus_adjust_plus))
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // ⏭ SKIP PHASE (recorded with real seconds, completed = false)
        OutlinedButton(onClick = onSkip) {
            Text(stringResource(R.string.pomodoro_skip))
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 📜 LIVE SESSION LOG (collapsible)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { logOpen = !logOpen },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.pomodoro_log),
                style = MaterialTheme.typography.titleSmall
            )
            Icon(
                imageVector = if (logOpen) Icons.Default.KeyboardArrowUp
                else Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(R.string.pomodoro_log)
            )
        }

        AnimatedVisibility(visible = logOpen) {
            Column(modifier = Modifier.fillMaxWidth()) {
                segments.forEach { seg ->
                    LogRow(
                        typeLabel = phaseLabel(
                            runCatching { SegmentType.valueOf(seg.type) }.getOrNull()
                        ),
                        actualSec = seg.actualSec.toLong(),
                        extensionSec = seg.extensionSec,
                        color = if (seg.type == SegmentType.FOCUS.name) {
                            MaterialTheme.colorScheme.primary
                        } else BreakGreen
                    )
                }
                // the segment currently running
                if (state.phase != null) {
                    LogRow(
                        typeLabel = phaseLabel(state.phase),
                        actualSec = state.actualRunMsAt(now) / 1000L,
                        extensionSec = state.extensionSecCurrentSegment,
                        color = phaseColor
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun LogRow(
    typeLabel: String,
    actualSec: Long,
    extensionSec: Int,
    color: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(typeLabel, style = MaterialTheme.typography.bodyMedium, color = color)
        val dur = formatClock(actualSec * 1000L)
        Text(
            text = if (extensionSec != 0) {
                val ext = (if (extensionSec > 0) "+" else "−") +
                    formatClock(kotlin.math.abs(extensionSec.toLong()) * 1000L)
                stringResource(R.string.log_ext_format, dur, ext)
            } else {
                dur
            },
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End
        )
    }
}

// ------------------------------------------------------------------ helpers

private fun formatClock(ms: Long): String = String.format(
    Locale.US,
    "%02d:%02d",
    (ms / 60_000L).coerceAtLeast(0L),
    ((ms % 60_000L) / 1000L).coerceAtLeast(0L)
)

@Composable
private fun phaseLabel(phase: SegmentType?): String = when (phase) {
    SegmentType.FOCUS -> stringResource(R.string.phase_focus)
    SegmentType.SHORT_BREAK -> stringResource(R.string.phase_short_break)
    SegmentType.LONG_BREAK -> stringResource(R.string.phase_long_break)
    null -> ""
}