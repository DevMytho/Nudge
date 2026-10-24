package com.dev.nudge.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dev.nudge.R
import com.dev.nudge.data.UserSettings
import com.dev.nudge.data.WeekStart
import com.dev.nudge.data.session.SegmentType
import com.dev.nudge.data.session.SessionSegmentEntity
import com.dev.nudge.model.DayCellState
import com.dev.nudge.model.EstimateAccuracy
import com.dev.nudge.model.ProgressViewModel
import com.dev.nudge.model.StatsCalculator
import com.dev.nudge.model.StreakCalculator
import com.dev.nudge.model.Task
import com.dev.nudge.model.TaskUtils
import kotlinx.coroutines.flow.Flow
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

private val GreenOk = Color(0xFF66BB6A)
private val AmberOver = Color(0xFFFFA726)
private val GoalYellow = Color(0xFFFFC94D)

/**
 * 📈 Progress tab (spec §8): streak, week stats, focus + accuracy charts
 * (hand-drawn Canvas) and a month calendar with per-day status rings and a
 * day-detail bottom sheet (tasks estimate-vs-actual + session timeline).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressScreen(
    tasks: List<Task>,
    progress: ProgressViewModel
) {

    val focusByDate by progress.focusSecondsByDate.collectAsState()
    val settings by progress.settings.collectAsState()

    val today = remember { LocalDate.now() }
    val goalMinutes = settings.dailyGoalMinutes
    val streakMin = settings.streakMinMinutes
    val weekStart = settings.weekStartsOn

    var displayedMonth by remember { mutableStateOf(YearMonth.from(today)) }
    var chartRange by remember { mutableStateOf(7) }
    var selectedDate by remember { mutableStateOf<String?>(null) }

    // 📊 derived inputs (precomputed once per input change — cheap)
    val plannedByDate = remember(tasks) {
        tasks.groupingBy { it.date }.eachCount()
    }
    val estimateByDate = remember(tasks) {
        tasks.mapNotNull { t ->
            val est = TaskUtils.estimatedMinutesEffective(t)
            if (est != null && t.date.isNotBlank()) t.date to est else null
        }.groupBy({ it.first }, { it.second }).mapValues { (_, v) -> v.sum() }
    }
    val daysWithFocus = remember(focusByDate, streakMin) {
        focusByDate.mapNotNull { (iso, sec) ->
            if (sec >= streakMin * 60) runCatching { LocalDate.parse(iso) }.getOrNull() else null
        }.toSet()
    }
    val streak = remember(daysWithFocus, today) {
        StreakCalculator.compute(daysWithFocus, today)
    }

    // week boundaries honouring weekStartsOn
    val weekStartDay = remember(today, weekStart) {
        val dow = today.dayOfWeek.value
        val back = if (weekStart == WeekStart.MON) dow - 1 else dow % 7
        today.minusDays(back.toLong())
    }
    val weekChange = remember(focusByDate, weekStartDay, today) {
        fun sum(from: LocalDate, to: LocalDate): Long {
            var total = 0L
            var d = from
            while (!d.isAfter(to)) {
                total += focusByDate[d.toString()] ?: 0
                d = d.plusDays(1)
            }
            return total
        }
        StatsCalculator.weekOverWeekPercent(
            sum(weekStartDay, today),
            sum(weekStartDay.minusDays(7), weekStartDay.minusDays(1))
        )
    }
    val bestDay = remember(focusByDate) { StatsCalculator.bestDay(focusByDate) }
    val monthTotal = remember(focusByDate) {
        StatsCalculator.totalForMonth(focusByDate, YearMonth.from(today).toString())
    }

    // 📅 day detail sheet
    selectedDate?.let { iso ->
        ModalBottomSheet(
            onDismissRequest = { selectedDate = null },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            DayDetailContent(
                date = iso,
                tasks = tasks,
                segmentsFlow = progress.segmentsForDate(iso),
                onClose = { selectedDate = null }
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            stringResource(R.string.progress_title),
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 🔥 STREAK CARD
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    StatBlock(
                        label = stringResource(R.string.streak_current_label),
                        value = if (streak.current == 1) {
                            stringResource(R.string.streak_day_count)
                        } else {
                            stringResource(R.string.streak_days_count, streak.current)
                        },
                        highlight = true
                    )
                    StatBlock(
                        label = stringResource(R.string.streak_best_label),
                        value = if (streak.best == 1) {
                            stringResource(R.string.streak_day_count)
                        } else {
                            stringResource(R.string.streak_days_count, streak.best)
                        }
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 7-day dot row of the current week
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(7) { i ->
                        val day = weekStartDay.plusDays(i.toLong())
                        val counted = day in daysWithFocus
                        val future = day.isAfter(today)
                        val color = when {
                            future -> MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                            counted -> GreenOk
                            else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
                        }
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(color)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 📊 HEADER STATS
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatBlock(
                label = stringResource(R.string.stats_week_change_label),
                value = weekChange?.let { String.format(Locale.US, "%+d%%", it) } ?: "—",
                modifier = Modifier.weight(1f)
            )
            StatBlock(
                label = stringResource(R.string.stats_best_day_label),
                value = bestDay?.let { iso ->
                    val d = runCatching { LocalDate.parse(iso) }.getOrNull()
                    val dateTxt = d?.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())) ?: iso
                    "$dateTxt · ${TaskUtils.formatMinutes((focusByDate[iso] ?: 0) / 60)}"
                } ?: "—",
                modifier = Modifier.weight(1f)
            )
            StatBlock(
                label = stringResource(R.string.stats_month_total_label),
                value = TaskUtils.formatMinutes(monthTotal / 60),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 📈 FOCUS CHART (7 / 30 days + dashed goal line)
        ChartCard(title = stringResource(R.string.chart_focus_title)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = chartRange == 7,
                    onClick = { chartRange = 7 },
                    label = { Text(stringResource(R.string.chart_range_7)) }
                )
                FilterChip(
                    selected = chartRange == 30,
                    onClick = { chartRange = 30 },
                    label = { Text(stringResource(R.string.chart_range_30)) }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            val rangeDays = remember(chartRange, today) {
                (chartRange downTo 1).map { today.minusDays((it - 1).toLong()) }
            }
            FocusBarChart(
                days = rangeDays,
                focusByDate = focusByDate,
                goalMinutes = goalMinutes
            )

            Spacer(modifier = Modifier.height(6.dp))
            LegendRow(
                color = MaterialTheme.colorScheme.outline,
                label = stringResource(
                    R.string.chart_goal_label,
                    TaskUtils.formatMinutes(goalMinutes)
                )
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 📉 ACCURACY CHART (actual ÷ estimated, 100 % line)
        ChartCard(title = stringResource(R.string.chart_accuracy_title)) {
            val accDays = remember(chartRange, today) {
                (chartRange downTo 1).map { today.minusDays((it - 1).toLong()) }
            }
            AccuracyBarChart(
                days = accDays,
                focusByDate = focusByDate,
                estimateByDate = estimateByDate
            )
            Spacer(modifier = Modifier.height(6.dp))
            LegendRow(
                color = MaterialTheme.colorScheme.outline,
                label = stringResource(R.string.chart_accuracy_line_label)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 📅 CALENDAR
        ChartCard(title = stringResource(R.string.progress_calendar)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { displayedMonth = displayedMonth.minusMonths(1) }) {
                    Icon(
                        Icons.Default.KeyboardArrowLeft,
                        contentDescription = stringResource(R.string.calendar_prev_month)
                    )
                }
                Text(
                    displayedMonth.format(
                        DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())
                    ),
                    style = MaterialTheme.typography.titleMedium
                )
                IconButton(onClick = { displayedMonth = displayedMonth.plusMonths(1) }) {
                    Icon(
                        Icons.Default.KeyboardArrowRight,
                        contentDescription = stringResource(R.string.calendar_next_month)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            val leadingBlanks = remember(displayedMonth, weekStart) {
                val dow = displayedMonth.atDay(1).dayOfWeek.value
                if (weekStart == WeekStart.MON) dow - 1 else dow % 7
            }
            val daysInMonth = displayedMonth.lengthOfMonth()
            val totalCells = 7 + leadingBlanks + daysInMonth
            val rows = (totalCells + 6) / 7
            val cellH = 52.dp
            val weekdayLabels = remember(weekStart) {
                val monFirst = weekStart == WeekStart.MON
                (0 until 7).map { i ->
                    val dowValue = if (monFirst) i + 1 else if (i == 0) 7 else i
                    DayOfWeek.of(dowValue).getDisplayName(TextStyle.SHORT, Locale.getDefault())
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(7),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(cellH * rows),
                userScrollEnabled = false
            ) {
                items(7) { i ->
                    Text(
                        weekdayLabels[i],
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(leadingBlanks) {
                    Spacer(modifier = Modifier.height(cellH))
                }
                items(daysInMonth) { idx ->
                    val date = displayedMonth.atDay(idx + 1)
                    val iso = date.toString()
                    val cellState = StatsCalculator.dayCellState(
                        plannedTasks = plannedByDate[iso] ?: 0,
                        actualSec = focusByDate[iso] ?: 0,
                        estimatedMinutes = estimateByDate[iso]
                    )
                    val goalReached = goalMinutes > 0 &&
                        (focusByDate[iso] ?: 0) >= goalMinutes * 60

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(cellH)
                            .clickable { selectedDate = iso }
                            .then(
                                if (date == today) Modifier.border(
                                    1.5.dp,
                                    MaterialTheme.colorScheme.primary,
                                    RoundedCornerShape(10.dp)
                                ) else Modifier
                            )
                            .padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            "${date.dayOfMonth}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (date == today) FontWeight.Bold else null,
                            color = if (date == today) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    when (cellState) {
                                        DayCellState.EMPTY -> Color.Transparent
                                        DayCellState.PLANNED ->
                                            MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                                        DayCellState.ON_ESTIMATE -> GreenOk
                                        DayCellState.OVER_ESTIMATE -> AmberOver
                                    }
                                )
                        )
                        if (goalReached) {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = null,
                                modifier = Modifier.size(10.dp),
                                tint = GoalYellow
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

// --------------------------------------------------------------- fragments

@Composable
private fun StatBlock(
    label: String,
    value: String,
    highlight: Boolean = false,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                color = if (highlight) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun ChartCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun LegendRow(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(18.dp)
                .height(2.dp)
                .background(color)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun DrawScope.dashedLine(
    y: Float,
    color: Color,
    strokeWidth: Float = 3f,
    dash: Float = 14f,
    gap: Float = 10f
) {
    var x = 0f
    while (x < size.width) {
        drawLine(
            color = color,
            start = Offset(x, y),
            end = Offset(minOf(x + dash, size.width), y),
            strokeWidth = strokeWidth
        )
        x += dash + gap
    }
}

// ----------------------------------------------------------------- charts

@Composable
private fun FocusBarChart(
    days: List<LocalDate>,
    focusByDate: Map<String, Int>,
    goalMinutes: Int
) {
    val primary = MaterialTheme.colorScheme.primary
    val goalColor = MaterialTheme.colorScheme.outline
    val anim = remember { Animatable(0f) }
    LaunchedEffect(days) {
        anim.snapTo(0f)
        anim.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
    }

    val maxMinutes = remember(days, focusByDate, goalMinutes) {
        val maxFocus = days.maxOfOrNull { (focusByDate[it.toString()] ?: 0) / 60 } ?: 0
        (maxOf(maxFocus, goalMinutes).coerceAtLeast(10) * 1.15f)
    }

    androidx.compose.foundation.Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(170.dp)
    ) {
        if (days.isEmpty()) return@Canvas
        val slot = size.width / days.size
        val barW = slot * 0.62f

        days.forEachIndexed { i, d ->
            val minutes = (focusByDate[d.toString()] ?: 0) / 60f
            val h = (minutes / maxMinutes) * size.height * anim.value
            if (h > 0f) {
                drawRect(
                    color = primary,
                    topLeft = Offset(i * slot + (slot - barW) / 2f, size.height - h),
                    size = Size(barW, h)
                )
            }
        }

        val goalY = size.height - (goalMinutes / maxMinutes) * size.height
        dashedLine(goalY, goalColor)
    }
}

@Composable
private fun AccuracyBarChart(
    days: List<LocalDate>,
    focusByDate: Map<String, Int>,
    estimateByDate: Map<String, Int>
) {
    val okColor = GreenOk
    val overColor = AmberOver
    val lineColor = MaterialTheme.colorScheme.outline
    val anim = remember { Animatable(0f) }
    LaunchedEffect(days) {
        anim.snapTo(0f)
        anim.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
    }

    // ratio per day (only days with an estimate), capped display at 1.5×
    val ratios = remember(days, focusByDate, estimateByDate) {
        days.map { d ->
            val estSec = (estimateByDate[d.toString()] ?: 0) * 60
            if (estSec > 0) (focusByDate[d.toString()] ?: 0).toFloat() / estSec else null
        }
    }
    val maxRatio = remember(ratios) {
        (ratios.filterNotNull().maxOrNull() ?: 1f).coerceAtLeast(1.2f).coerceAtMost(2f)
    }

    androidx.compose.foundation.Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
    ) {
        if (days.isEmpty()) return@Canvas
        val slot = size.width / days.size
        val barW = slot * 0.62f

        ratios.forEachIndexed { i, ratio ->
            if (ratio == null) return@forEachIndexed
            val h = (ratio / maxRatio) * size.height * anim.value
            if (h > 0f) {
                drawRect(
                    color = if (ratio <= 1f) okColor else overColor,
                    topLeft = Offset(i * slot + (slot - barW) / 2f, size.height - h),
                    size = Size(barW, h)
                )
            }
        }

        val lineY = size.height - (1f / maxRatio) * size.height
        dashedLine(lineY, lineColor)
    }
}

// ------------------------------------------------------------ day detail

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayDetailContent(
    date: String,
    tasks: List<Task>,
    segmentsFlow: Flow<List<SessionSegmentEntity>>,
    onClose: () -> Unit
) {
    val segments by segmentsFlow.collectAsState(initial = emptyList())
    val dayTasks = remember(tasks, date) {
        TaskUtils.sortTasks(tasks.filter { it.date == date })
    }
    val totals = remember(segments) { StatsCalculator.dayTotals(segments) }
    val dateLabel = remember(date) {
        runCatching { LocalDate.parse(date).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)) }
            .getOrDefault(date)
    }
    val doneCount = dayTasks.count { it.isCompleted }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        Text(dateLabel, style = MaterialTheme.typography.titleLarge)

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatBlock(
                label = stringResource(R.string.day_detail_done),
                value = "$doneCount / ${dayTasks.size}",
                modifier = Modifier.weight(1f)
            )
            StatBlock(
                label = stringResource(R.string.day_detail_focus),
                value = TaskUtils.formatMinutes(totals.focusSec / 60),
                modifier = Modifier.weight(1f)
            )
            StatBlock(
                label = stringResource(R.string.day_detail_break),
                value = TaskUtils.formatMinutes(totals.breakSec / 60),
                modifier = Modifier.weight(1f)
            )
            StatBlock(
                label = stringResource(R.string.day_detail_cycles),
                value = "${totals.cycles}",
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // tasks: estimate vs actual + accuracy marker, struck when done
        dayTasks.forEach { task ->
            DayTaskRow(task = task, segments = segments)
        }

        if (segments.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                stringResource(R.string.day_detail_sessions),
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(modifier = Modifier.height(6.dp))

            val sessionGroups = remember(segments) {
                segments.groupBy { it.sessionId }.values
                    .map { segs -> segs.sortedBy { it.index } to (segs.minOfOrNull { it.startedAt } ?: 0L) }
                    .sortedBy { it.second }
            }

            sessionGroups.forEach { (segs, startAt) ->
                val startTxt = remember(startAt) {
                    java.text.SimpleDateFormat("HH:mm", Locale.getDefault())
                        .format(java.util.Date(startAt))
                }
                Text(
                    startTxt,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                segs.forEach { seg ->
                    DaySegmentRow(seg = seg)
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        if (dayTasks.isEmpty() && segments.isEmpty()) {
            Text(
                stringResource(R.string.day_detail_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.summary_close))
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun DayTaskRow(task: Task, segments: List<SessionSegmentEntity>) {
    val actualSec = remember(task, segments) {
        segments.filter { it.taskId == task.id && it.segmentType == SegmentType.FOCUS }
            .sumOf { it.actualSec }
    }
    val estimate = TaskUtils.estimatedMinutesEffective(task)
    val accuracy = StatsCalculator.classify(actualSec, estimate)

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            task.title,
            style = MaterialTheme.typography.bodyLarge,
            textDecoration = if (task.isCompleted) TextDecoration.LineThrough else null,
            color = if (task.isCompleted) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (estimate != null) {
                Text(
                    stringResource(R.string.chip_estimate_minutes, TaskUtils.formatMinutes(estimate)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    " · ",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                stringResource(
                    R.string.chip_actual,
                    TaskUtils.formatMinutes(actualSec / 60)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    estimate == null -> MaterialTheme.colorScheme.onSurfaceVariant
                    accuracy == EstimateAccuracy.OVER -> AmberOver
                    else -> GreenOk
                }
            )
            if (accuracy == EstimateAccuracy.ON && estimate != null) {
                Text(
                    " ✓",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = GreenOk
                )
            } else if (accuracy == EstimateAccuracy.OVER) {
                Text(
                    " ✗",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = AmberOver
                )
            }
        }
    }
}

@Composable
private fun DaySegmentRow(seg: SessionSegmentEntity) {
    val isFocus = seg.segmentType == SegmentType.FOCUS
    val color = if (isFocus) MaterialTheme.colorScheme.primary else GreenOk
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            when (seg.segmentType) {
                SegmentType.FOCUS -> stringResource(R.string.phase_focus)
                SegmentType.SHORT_BREAK -> stringResource(R.string.phase_short_break)
                SegmentType.LONG_BREAK -> stringResource(R.string.phase_long_break)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = color
        )
        val dur = String.format(
            Locale.US, "%02d:%02d", seg.actualSec / 60, seg.actualSec % 60
        )
        Text(
            text = if (seg.extensionSec != 0) {
                val ext = (if (seg.extensionSec > 0) "+" else "−") +
                    String.format(
                        Locale.US, "%02d:%02d",
                        Math.abs(seg.extensionSec) / 60,
                        Math.abs(seg.extensionSec) % 60
                    )
                stringResource(R.string.log_ext_format, dur, ext)
            } else dur,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End
        )
    }
}
