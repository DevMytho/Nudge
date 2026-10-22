package com.dev.nudge.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dev.nudge.CardBeige
import com.dev.nudge.R
import com.dev.nudge.components.TaskCard
import com.dev.nudge.model.Task
import com.dev.nudge.model.TaskUtils
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private enum class TaskView { DAY, ALL }

/**
 * 📅 Tasks tab (spec §5): date strip, day summary, Day/All views, carried
 * over section, strike-off in place, collapsing header that hides on scroll.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    tasks: List<Task>,
    selectedDate: String,
    actualSecondsByTask: Map<String, Int>,
    hideCompleted: Boolean,
    onSelectDate: (String) -> Unit,
    onToggleHideCompleted: () -> Unit,
    onToggleComplete: (Task) -> Unit,
    onEdit: (Task) -> Unit,
    onDelete: (Task) -> Unit,
    onStart: (Task) -> Unit,
    onUpdate: (Task) -> Unit
) {

    val todayIso = TaskUtils.todayIso()

    var view by remember { mutableStateOf(TaskView.DAY) }
    var showDatePicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = isoToUtcMillis(selectedDate)
    )

    // 📅 MONTH PICKER
    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            colors = DatePickerDefaults.colors(
                containerColor = CardBeige,
                titleContentColor = Color.Black,
                headlineContentColor = Color.Black,
                weekdayContentColor = Color.Black,
                subheadContentColor = Color.Black
            ),
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        onSelectDate(utcMillisToIso(millis))
                    }
                    showDatePicker = false
                }) { Text(stringResource(R.string.ok), color = Color.Black) }
            }
        ) {
            DatePicker(
                state = datePickerState,
                colors = DatePickerDefaults.colors(
                    containerColor = CardBeige,
                    titleContentColor = Color.Black,
                    headlineContentColor = Color.Black,
                    weekdayContentColor = Color.Black,
                    subheadContentColor = Color.Black,
                    dayContentColor = Color.Black,
                    disabledDayContentColor = Color.Gray,
                    yearContentColor = Color.Black,
                    navigationContentColor = Color.Black,
                    dividerColor = Color.Black.copy(alpha = 0.2f),
                    selectedDayContainerColor = Color(0xFFC8D9E6),
                    selectedDayContentColor = Color.Black,
                    todayDateBorderColor = Color(0xFFC8D9E6),
                    selectedYearContainerColor = Color(0xFFC8D9E6),
                    selectedYearContentColor = Color.Black
                )
            )
        }
    }

    val listState = rememberLazyListState()
    val fabScope = rememberCoroutineScope()

    // 🔥 Hide the header/strip on scroll down, show again on scroll up
    val headerVisible = remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        var prevIndex = listState.firstVisibleItemIndex
        var prevOffset = listState.firstVisibleItemScrollOffset
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collect { (index, offset) ->
            val goingDown =
                index > prevIndex || (index == prevIndex && offset > prevOffset)
            val atTop = index == 0 && offset == 0
            headerVisible.value = atTop || !goingDown
            prevIndex = index
            prevOffset = offset
        }
    }

    // 🧮 Precomputed per-day values (cheap, once per recomposition of the header)
    val dayTasks = TaskUtils.tasksForDate(tasks, selectedDate)
    val visibleDayTasks =
        if (hideCompleted) dayTasks.filter { !it.isCompleted } else dayTasks
    val carriedOver = TaskUtils.carriedOver(tasks, selectedDate)
        .let { if (hideCompleted) it.filter { t -> !t.isCompleted } else it }

    val doneCount = dayTasks.count { it.isCompleted }
    val actualMinutes = dayTasks.sumOf { (actualSecondsByTask[it.id] ?: 0) / 60 }
    val estimatedMinutes = dayTasks.sumOf { TaskUtils.estimatedMinutesEffective(it) ?: 0 }
    val anyEstimate = dayTasks.any { TaskUtils.estimatedMinutesEffective(it) != null }

    val allGroups = remember(tasks, hideCompleted) {
        val filtered =
            if (hideCompleted) tasks.filter { !it.isCompleted } else tasks
        TaskUtils.dayGroups(filtered)
    }

    // 📌 In All view, land on today
    LaunchedEffect(allGroups.size, view) {
        if (view == TaskView.ALL) {
            val index = headerIndexFor(allGroups, todayIso)
            if (index >= 0) listState.scrollToItem(index)
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text(greeting())
                        Text(
                            text = formatDisplayDate(selectedDate),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showDatePicker = true }) {
                        Icon(
                            Icons.Default.CalendarMonth,
                            contentDescription = stringResource(R.string.action_pick_date)
                        )
                    }
                    var menuOpen by remember { mutableStateOf(false) }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.action_more)
                        )
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.hide_completed))
                                    Checkbox(
                                        checked = hideCompleted,
                                        onCheckedChange = { onToggleHideCompleted() }
                                    )
                                }
                            },
                            onClick = { onToggleHideCompleted() }
                        )
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->

        Box(modifier = Modifier.padding(innerPadding)) {

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 110.dp)
            ) {

                // 📅 DATE STRIP + DAY SUMMARY + VIEW TOGGLE (scroll away with content)
                item(key = "header") {
                    AnimatedVisibility(
                        visible = headerVisible.value,
                        enter = slideInVertically(animationSpec = tween(200)) { -it } + fadeIn(),
                        exit = slideOutVertically(animationSpec = tween(200)) { -it } + fadeOut()
                    ) {
                        Column {

                            Spacer(modifier = Modifier.height(4.dp))

                            // week strip: -3 … +21 days around today, today preselected
                            val stripDays = remember {
                                (-3..21).map { offset ->
                                    TaskUtils.isoPlusDays(todayIso, offset)
                                }
                            }
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(stripDays, key = { it }) { iso ->
                                    DateChip(
                                        iso = iso,
                                        selected = iso == selectedDate,
                                        isToday = iso == todayIso,
                                        hasTasks = tasks.any { it.date == iso && (!hideCompleted || !it.isCompleted) },
                                        onClick = {
                                            onSelectDate(iso)
                                            view = TaskView.DAY
                                        }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // 🔥 DAY SUMMARY
                            Text(
                                text = stringResource(
                                    R.string.day_summary,
                                    doneCount,
                                    dayTasks.size
                                ),
                                style = MaterialTheme.typography.titleSmall
                            )

                            if (anyEstimate || actualMinutes > 0) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (anyEstimate) {
                                        stringResource(
                                            R.string.day_summary_focus_of_estimate,
                                            TaskUtils.formatMinutes(actualMinutes),
                                            TaskUtils.formatMinutes(estimatedMinutes)
                                        )
                                    } else {
                                        stringResource(
                                            R.string.day_summary_focus,
                                            TaskUtils.formatMinutes(actualMinutes)
                                        )
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                if (anyEstimate && estimatedMinutes > 0) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    LinearProgressIndicator(
                                        progress = {
                                            (actualMinutes.toFloat() / estimatedMinutes)
                                                .coerceIn(0f, 1f)
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(6.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // 🔀 Day | All
                            SingleChoiceSegmentedButtonRow(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                TaskView.entries.forEachIndexed { index, mode ->
                                    SegmentedButton(
                                        selected = view == mode,
                                        onClick = { view = mode },
                                        shape = SegmentedButtonDefaults.itemShape(
                                            index = index,
                                            count = TaskView.entries.size
                                        ),
                                        label = {
                                            Text(
                                                stringResource(
                                                    if (mode == TaskView.DAY) R.string.view_day
                                                    else R.string.view_all
                                                )
                                            )
                                        }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                }

                when (view) {
                    TaskView.DAY -> {

                        // 🔁 CARRIED OVER
                        if (carriedOver.isNotEmpty()) {
                            stickyHeader("carried") {
                                SectionHeader(stringResource(R.string.carried_over))
                            }
                            items(carriedOver, key = { "carried-${it.id}" }) { task ->
                                CarriedOverCard(
                                    task = task,
                                    targetDateLabel = formatDisplayDate(selectedDate),
                                    onToggleComplete = { onToggleComplete(task) },
                                    onEdit = { onEdit(task) },
                                    onDelete = { onDelete(task) },
                                    onStart = { onStart(task) },
                                    onMoveToToday = {
                                        onUpdate(task.copy(date = selectedDate))
                                    }
                                )
                            }
                        }

                        // 📅 SELECTED DAY
                        if (visibleDayTasks.isEmpty()) {
                            item(key = "empty") {
                                Text(
                                    text = stringResource(
                                        if (carriedOver.isEmpty()) R.string.empty_day
                                        else R.string.empty_day_after_carry
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 24.dp)
                                )
                            }
                        } else {
                            items(visibleDayTasks, key = { it.id }) { task ->
                                TaskCard(
                                    task = task,
                                    actualSeconds = actualSecondsByTask[task.id] ?: 0,
                                    onClick = { onEdit(task) },
                                    onChecked = { onToggleComplete(task) },
                                    onStart = { onStart(task) },
                                    onEdit = { onEdit(task) },
                                    onDelete = { onDelete(task) },
                                    modifier = Modifier.animateItem()
                                )
                            }
                        }
                    }

                    TaskView.ALL -> {
                        allGroups.forEachIndexed { index, (iso, groupTasks) ->
                            stickyHeader("date-$iso") {
                                SectionHeader(formatDisplayDate(iso))
                            }
                            items(groupTasks, key = { it.id }) { task ->
                                TaskCard(
                                    task = task,
                                    actualSeconds = actualSecondsByTask[task.id] ?: 0,
                                    onClick = { onEdit(task) },
                                    onChecked = { onToggleComplete(task) },
                                    onStart = { onStart(task) },
                                    onEdit = { onEdit(task) },
                                    onDelete = { onDelete(task) },
                                    modifier = Modifier.animateItem()
                                )
                            }
                        }
                    }
                }
            }

            // ⬆️ scroll-to-top, appears after the first screen
            AnimatedVisibility(
                visible = listState.isScrolledPastFirstScreen(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 110.dp),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                SmallFloatingActionButton(
                    onClick = {
                        fabScope.launch { listState.animateScrollToItem(0) }
                    },
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.action_scroll_top)
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(top = 12.dp, bottom = 6.dp)
    )
}

@Composable
private fun CarriedOverCard(
    task: Task,
    targetDateLabel: String,
    onToggleComplete: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onStart: () -> Unit,
    onMoveToToday: () -> Unit
) {
    Column {
        TaskCard(
            task = task,
            onClick = onEdit,
            onChecked = onToggleComplete,
            onStart = onStart,
            onEdit = onEdit,
            onDelete = onDelete
        )
        TextButton(onClick = onMoveToToday) {
            Text(stringResource(R.string.move_to_date, targetDateLabel))
        }
    }
}

@Composable
private fun DateChip(
    iso: String,
    selected: Boolean,
    isToday: Boolean,
    hasTasks: Boolean,
    onClick: () -> Unit
) {
    val dayName = remember(iso) {
        SimpleDateFormat("EEE", Locale.getDefault()).format(
            TaskUtils.parseIsoDate(iso) ?: Date()
        )
    }
    val dayNumber = remember(iso) { iso.substringAfterLast('-').toIntOrNull() ?: 0 }

    val bg = when {
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surface
    }
    val fg = when {
        selected -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .background(bg, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(dayName, style = MaterialTheme.typography.labelSmall, color = fg)
        Text(
            text = dayNumber.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
            color = fg
        )
        Box(
            modifier = Modifier
                .size(4.dp)
                .background(
                    color = if (hasTasks) fg.copy(alpha = 0.8f) else Color.Transparent,
                    shape = androidx.compose.foundation.shape.CircleShape
                )
        )
    }
}

// ------------------------------------------------------------------ helpers

private fun LazyListState.isScrolledPastFirstScreen(): Boolean =
    firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > 400

/** Index of the sticky header for [isoDate] within the flat LazyColumn item list. */
private fun headerIndexFor(groups: List<Pair<String, List<Task>>>, isoDate: String): Int {
    var index = 0
    for ((iso, tasksInGroup) in groups) {
        if (iso == isoDate) return index
        index += 1 + tasksInGroup.size
    }
    return -1
}

private fun isoToUtcMillis(iso: String): Long? = try {
    SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
        isLenient = false
    }.parse(iso)?.time
} catch (e: Exception) {
    null
}

private fun utcMillisToIso(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(millis))

private fun greeting(): String = when (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    else -> "Good evening"
}

private fun formatDisplayDate(iso: String): String {
    val parsed = TaskUtils.parseIsoDate(iso) ?: return iso
    return SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()).format(parsed)
}
