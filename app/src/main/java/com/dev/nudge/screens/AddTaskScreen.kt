package com.dev.nudge.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dev.nudge.CardBeige
import com.dev.nudge.R
import com.dev.nudge.data.UserSettings
import com.dev.nudge.model.Priority
import com.dev.nudge.model.Task
import com.dev.nudge.model.TaskUtils
import com.dev.nudge.ui.theme.DarkText
import java.text.SimpleDateFormat
import java.util.*
import java.util.UUID

private enum class EstimateMode { MINUTES, CYCLES }

/**
 * ✏️ Add / edit a task.
 *
 * Deadline (dueTime) and estimate are independent (spec §2): the deadline
 * drives reminders and countdown labels, the estimate only feeds planning
 * and the estimated-vs-actual comparison.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTaskScreen(
    existingTask: Task? = null,
    defaultDate: String = TaskUtils.todayIso(),
    onSave: (Task) -> Unit,
    onDelete: ((Task) -> Unit)? = null, // 🗑 delete when editing
    onBack: () -> Unit
) {

    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    val skyBlue = Color(0xFFC8D9E6)

    val cardColor = if (isDark) Color.Black else CardBeige
    val textColor = if (isDark) CardBeige else Color.Black
    val accent = if (isDark) CardBeige else skyBlue

    // 🔥 PREFILLED STATES (EDIT SUPPORT)
    var title by remember { mutableStateOf(existingTask?.title ?: "") }
    var date by remember { mutableStateOf(existingTask?.date ?: defaultDate) }
    var dueTime by remember { mutableStateOf(existingTask?.dueTime ?: "") }
    var priority by remember { mutableStateOf(existingTask?.priorityLevel ?: Priority.LOW) }
    var isCritical by remember { mutableStateOf(existingTask?.critical ?: false) }

    // 📊 ESTIMATE (independent from the deadline)
    var estimateMode by remember {
        mutableStateOf(
            if (existingTask?.estimatedCycles != null) EstimateMode.CYCLES
            else EstimateMode.MINUTES
        )
    }
    var estimatedMinutes by remember {
        mutableStateOf(existingTask?.estimatedMinutes ?: UserSettings.DEFAULT_FOCUS_MINUTES)
    }
    var estimatedCycles by remember {
        mutableStateOf(existingTask?.estimatedCycles ?: 1)
    }
    // 🔥 cycle length snapshot: the focus length at the time of entry, so
    //    later settings edits never rewrite this task's estimate
    var cycleSnapshot by remember {
        mutableStateOf(existingTask?.cycleMinutesSnapshot ?: UserSettings.DEFAULT_FOCUS_MINUTES)
    }

    var showTimePicker by remember { mutableStateOf(false) }
    val dateState = rememberDatePickerState()
    var showDatePicker by remember { mutableStateOf(false) }

    val isValid = title.isNotBlank() && date.isNotBlank()

    // 📅 DATE PICKER
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
                    dateState.selectedDateMillis?.let { millis ->
                        // 🔑 DatePicker works in UTC midnight → format in UTC to
                        //    get the exact day the user tapped (any timezone)
                        date = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                            timeZone = TimeZone.getTimeZone("UTC")
                        }.format(Date(millis))
                    }
                    showDatePicker = false
                }) {
                    Text(stringResource(R.string.ok), color = Color.Black)
                }
            }
        ) {

            DatePicker(
                state = dateState,
                colors = DatePickerDefaults.colors(

                    containerColor = CardBeige,

                    // 🔥 TEXT COLORS (force everything readable)
                    titleContentColor = Color.Black,
                    headlineContentColor = Color.Black,
                    weekdayContentColor = Color.Black,
                    subheadContentColor = Color.Black,

                    dayContentColor = Color.Black,
                    disabledDayContentColor = Color.Gray,

                    yearContentColor = Color.Black,

                    navigationContentColor = Color.Black,
                    dividerColor = Color.Black.copy(alpha = 0.2f),

                    // 🔥 SELECTED STATES
                    selectedDayContainerColor =
                        if (isDark) Color.Black else Color(0xFFC8D9E6),

                    selectedDayContentColor =
                        if (isDark) CardBeige else Color.Black,

                    todayDateBorderColor =
                        if (isDark) Color.Black else Color(0xFFC8D9E6),

                    selectedYearContainerColor =
                        if (isDark) Color.Black else Color(0xFFC8D9E6),

                    selectedYearContentColor =
                        if (isDark) CardBeige else Color.Black
                )
            )
        }
    }

    // ⏰ DEADLINE TIME PICKER (optional)
    if (showTimePicker) {
        val timeState = rememberTimePickerState()

        AlertDialog(
            onDismissRequest = { showTimePicker = false },

            containerColor = CardBeige,

            confirmButton = {
                TextButton(onClick = {
                    dueTime = String.format(Locale.US, "%02d:%02d", timeState.hour, timeState.minute)
                    showTimePicker = false
                }) {
                    Text(stringResource(R.string.ok), color = Color.Black)
                }
            },

            dismissButton = {
                if (dueTime.isNotBlank()) {
                    TextButton(onClick = {
                        dueTime = ""
                        showTimePicker = false
                    }) {
                        Text(stringResource(R.string.clear), color = Color.Black)
                    }
                }
            },

            text = {
                TimePicker(
                    state = timeState,
                    colors = TimePickerDefaults.colors(

                        containerColor = CardBeige,
                        clockDialColor = CardBeige,

                        clockDialSelectedContentColor = if (isDark) MaterialTheme.colorScheme.primary else Color.Black,
                        clockDialUnselectedContentColor = Color.Black,

                        selectorColor = if (isDark) Color.Black else Color(0xFFC8D9E6),

                        timeSelectorSelectedContainerColor =
                            if (isDark) Color.Black else Color(0xFFC8D9E6),

                        timeSelectorUnselectedContainerColor = CardBeige,

                        timeSelectorSelectedContentColor =
                            if (isDark) CardBeige else Color.Black,

                        timeSelectorUnselectedContentColor = Color.Black,

                        periodSelectorSelectedContainerColor =
                            if (isDark) Color.Black else Color(0xFFC8D9E6),

                        periodSelectorUnselectedContainerColor = CardBeige,

                        periodSelectorSelectedContentColor =
                            if (isDark) CardBeige else Color.Black,

                        periodSelectorUnselectedContentColor = Color.Black
                    )
                )
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {

        // 🔥 HEADER
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, null, tint = textColor)
            }

            Spacer(modifier = Modifier.width(12.dp))

            Text(
                if (existingTask == null) {
                    stringResource(R.string.new_task)
                } else {
                    stringResource(R.string.edit_task)
                },
                style = MaterialTheme.typography.headlineMedium,
                color = textColor
            )
        }

        Spacer(modifier = Modifier.height(30.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = cardColor),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {

            Column(modifier = Modifier.padding(16.dp)) {

                Text(stringResource(R.string.field_title), color = textColor)

                Spacer(modifier = Modifier.height(6.dp))

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 📅 PLANNED DAY
                Text(stringResource(R.string.field_day), color = textColor)

                Spacer(modifier = Modifier.height(6.dp))

                OutlinedTextField(
                    value = date,
                    onValueChange = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showDatePicker = true },
                    placeholder = { Text("yyyy-MM-dd") },
                    enabled = false
                )

                Spacer(modifier = Modifier.height(16.dp))

                // ⏰ DEADLINE (optional — drives reminders, not timer length)
                Text(stringResource(R.string.field_deadline), color = textColor)

                Spacer(modifier = Modifier.height(6.dp))

                OutlinedTextField(
                    value = dueTime,
                    onValueChange = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showTimePicker = true },
                    placeholder = { Text(stringResource(R.string.deadline_placeholder)) },
                    enabled = false
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 🎯 PRIORITY
                Text(stringResource(R.string.field_priority), color = textColor)

                Spacer(modifier = Modifier.height(6.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Priority.entries.forEach {

                        val isSelected = priority == it

                        FilterChip(
                            selected = isSelected,
                            onClick = { priority = it },
                            label = {
                                Text(
                                    it.name.lowercase().replaceFirstChar { c -> c.uppercase() },
                                    color = DarkText
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = accent
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = { isCritical = !isCritical },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (isCritical) stringResource(R.string.marked_critical)
                        else stringResource(R.string.mark_critical)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 📊 ESTIMATE (independent from the deadline)
                Text(stringResource(R.string.field_estimate), color = textColor)

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    stringResource(R.string.estimate_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = estimateMode == EstimateMode.MINUTES,
                        onClick = { estimateMode = EstimateMode.MINUTES },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                        label = { Text(stringResource(R.string.estimate_minutes)) }
                    )
                    SegmentedButton(
                        selected = estimateMode == EstimateMode.CYCLES,
                        onClick = {
                            estimateMode = EstimateMode.CYCLES
                            // snapshot the current cycle length on first switch
                            if (cycleSnapshot <= 0) {
                                cycleSnapshot = UserSettings.DEFAULT_FOCUS_MINUTES
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                        label = { Text(stringResource(R.string.estimate_cycles)) }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                when (estimateMode) {
                    EstimateMode.MINUTES -> {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            listOf(15, 25, 45, 60, 90).forEach { preset ->
                                FilterChip(
                                    selected = estimatedMinutes == preset,
                                    onClick = { estimatedMinutes = preset },
                                    label = { Text("${preset}m", color = DarkText) }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        StepperRow(
                            value = estimatedMinutes,
                            step = 5,
                            minValue = 5,
                            maxValue = 480,
                            format = { "${it}m" },
                            onChange = { estimatedMinutes = it }
                        )
                    }

                    EstimateMode.CYCLES -> {
                        StepperRow(
                            value = estimatedCycles,
                            step = 1,
                            minValue = 1,
                            maxValue = 12,
                            // plain template (no composable stringResource inside
                            // the non-composable format callback)
                            format = { cycles ->
                                "$cycles × ${TaskUtils.formatMinutes(cycles * cycleSnapshot)}"
                            },
                            onChange = { estimatedCycles = it }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                TextButton(onClick = {
                    estimatedMinutes = 0
                    estimatedCycles = 0
                }) {
                    Text(stringResource(R.string.estimate_clear))
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (!isValid) {
                    Text(
                        stringResource(R.string.fill_all_fields),
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Button(
                    onClick = {

                        val hasEstimate = when (estimateMode) {
                            EstimateMode.MINUTES -> estimatedMinutes > 0
                            EstimateMode.CYCLES -> estimatedCycles > 0
                        }

                        val task = Task(
                            id = existingTask?.id ?: UUID.randomUUID().toString(),
                            title = title,
                            date = date,
                            dueTime = dueTime.takeIf { it.isNotBlank() },
                            priority = priority.name,
                            critical = isCritical,
                            estimatedMinutes = if (estimateMode == EstimateMode.MINUTES && hasEstimate) {
                                estimatedMinutes
                            } else null,
                            estimatedCycles = if (estimateMode == EstimateMode.CYCLES && hasEstimate) {
                                estimatedCycles
                            } else null,
                            cycleMinutesSnapshot = if (hasEstimate) cycleSnapshot else null,
                            order = existingTask?.order
                                ?: (System.currentTimeMillis() / 60_000L).toInt(),
                            isCompleted = existingTask?.isCompleted ?: false,
                            completedAt = existingTask?.completedAt,
                            createdAt = existingTask?.createdAt ?: System.currentTimeMillis()
                        )

                        onSave(task)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = isValid
                ) {
                    Text(
                        if (existingTask == null) stringResource(R.string.add_task)
                        else stringResource(R.string.update_task)
                    )
                }

                // 🗑 DELETE (edit mode only)
                if (existingTask != null && onDelete != null) {
                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = { onDelete(existingTask) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.delete_task))
                    }
                }
            }
        }
    }
}

/** ➖➕ Generic stepper used by both estimate modes. */
@Composable
private fun StepperRow(
    value: Int,
    step: Int,
    minValue: Int,
    maxValue: Int,
    format: (Int) -> String,
    onChange: (Int) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(
            onClick = { onChange((value - step).coerceAtLeast(minValue)) },
            enabled = value > minValue
        ) { Text("−") }

        Text(
            text = format(value),
            style = MaterialTheme.typography.titleMedium
        )

        OutlinedButton(
            onClick = { onChange((value + step).coerceAtMost(maxValue)) },
            enabled = value < maxValue
        ) { Text("+") }
    }
}
