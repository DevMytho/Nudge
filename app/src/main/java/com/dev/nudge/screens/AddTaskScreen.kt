package com.dev.nudge.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.dev.nudge.CardBeige
import com.dev.nudge.model.Task
import com.dev.nudge.ui.theme.DarkText
import java.text.SimpleDateFormat
import java.util.*
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTaskScreen(
    existingTask: Task? = null, // 🔥 NEW
    onSave: (Task) -> Unit,     // 🔥 CHANGED
    onBack: () -> Unit
) {

    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    val skyBlue = Color(0xFFC8D9E6)

    val cardColor = if (isDark) Color.Black else CardBeige
    val textColor = if (isDark) CardBeige else Color.Black
    val accent = if (isDark) CardBeige else skyBlue

    // 🔥 PREFILLED STATES (EDIT SUPPORT)
    var title by remember { mutableStateOf(existingTask?.title ?: "") }
    var date by remember { mutableStateOf(existingTask?.date ?: "") }
    var time by remember { mutableStateOf(existingTask?.time ?: "") }
    var priority by remember { mutableStateOf(existingTask?.priority ?: "Low") }
    var isCritical by remember { mutableStateOf(existingTask?.isCritical ?: false) }

    var showTimePicker by remember { mutableStateOf(false) }
    val dateState = rememberDatePickerState()
    val timeState = rememberTimePickerState()
    var showDatePicker by remember { mutableStateOf(false) }

    val isValid = title.isNotBlank() && date.isNotBlank() && time.isNotBlank()

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
                    dateState.selectedDateMillis?.let {
                        val sdf = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())
                        date = sdf.format(Date(it))
                    }
                    showDatePicker = false
                }) {
                    Text("OK", color = Color.Black)
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

    if (showTimePicker) {
        AlertDialog(
            onDismissRequest = { showTimePicker = false },

            containerColor = CardBeige,

            confirmButton = {
                TextButton(onClick = {
                    time = String.format("%02d:%02d", timeState.hour, timeState.minute)
                    showTimePicker = false
                }) {
                    Text("OK", color = Color.Black)
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
    ) {

        // 🔥 HEADER
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, null, tint = textColor)
            }

            Spacer(modifier = Modifier.width(12.dp))

            Text(
                if (existingTask == null) "New Task" else "Edit Task", // 🔥 DYNAMIC
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

                Text("Task Title", color = textColor)

                Spacer(modifier = Modifier.height(6.dp))

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text("Deadline", color = textColor)

                Spacer(modifier = Modifier.height(6.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {

                    OutlinedTextField(
                        value = date,
                        onValueChange = {},
                        modifier = Modifier
                            .weight(1f)
                            .clickable { showDatePicker = true },
                        enabled = false
                    )

                    OutlinedTextField(
                        value = time,
                        onValueChange = {},
                        modifier = Modifier
                            .weight(1f)
                            .clickable { showTimePicker = true },
                        enabled = false
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text("Priority", color = textColor)

                Spacer(modifier = Modifier.height(6.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Low", "Medium", "High").forEach {

                        val isSelected = priority == it

                        FilterChip(
                            selected = isSelected,
                            onClick = { priority = it },
                            label = { Text(
                                it,
                                color = DarkText
                            ) },
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
                    Text(if (isCritical) "Marked Critical" else "Mark Critical")
                }

                Spacer(modifier = Modifier.height(20.dp))

                if (!isValid) {
                    Text(
                        "Please fill all fields",
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Button(
                    onClick = {

                        val task = Task(
                            id = existingTask?.id ?: UUID.randomUUID().toString(),
                            title = title,
                            time = time,
                            priority = priority,
                            isCritical = isCritical,
                            date = date
                        )

                        onSave(task)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = isValid
                ) {
                    Text(if (existingTask == null) "Add Task" else "Update Task")
                }
            }
        }
    }
}