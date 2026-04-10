package com.dev.nudge.screens

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dev.nudge.model.Task
import com.dev.nudge.components.TaskCard
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun HomeScreen(
    tasks: List<Task>,
    onRemove: (Task) -> Unit,
    onEdit: (Task) -> Unit
) {

    val today = Date()

    // 🔥 DATE HELPERS
    fun parseDate(date: String): Date? {
        return try {
            SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).parse(date)
        } catch (e: Exception) {
            null
        }
    }

    fun isSameDay(d1: Date, d2: Date): Boolean {
        val cal1 = Calendar.getInstance().apply { time = d1 }
        val cal2 = Calendar.getInstance().apply { time = d2 }

        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }

    fun isTomorrow(taskDate: Date): Boolean {
        val cal = Calendar.getInstance()
        cal.time = today
        cal.add(Calendar.DAY_OF_YEAR, 1)
        return isSameDay(taskDate, cal.time)
    }

    // 🔥 PRIORITY SORT
    fun priorityValue(task: Task): Int {
        return when (task.priority) {
            "High" -> 1
            "Medium" -> 2
            else -> 3
        }
    }

    // 🔥 GROUP TASKS
    val todayTasks = mutableListOf<Task>()
    val tomorrowTasks = mutableListOf<Task>()
    val upcomingTasks = mutableListOf<Task>()

    tasks.forEach { task ->
        val parsed = parseDate(task.date)

        if (parsed != null) {
            when {
                isSameDay(parsed, today) -> todayTasks.add(task)
                isTomorrow(parsed) -> tomorrowTasks.add(task)
                parsed.after(today) -> upcomingTasks.add(task)
            }
        }
    }

    // 🔥 SORT FUNCTION (CRITICAL FIRST + PRIORITY)
    fun sortTasks(list: List<Task>): List<Task> {
        return list.sortedWith(
            compareBy<Task> { !it.isCritical }
                .thenBy { priorityValue(it) }
        )
    }

    val sortedToday = sortTasks(todayTasks)
    val sortedTomorrow = sortTasks(tomorrowTasks)
    val sortedUpcoming = sortTasks(upcomingTasks)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 24.dp)
    ) {

        // 🔥 TODAY
        Text("Today", style = MaterialTheme.typography.headlineMedium)

        Spacer(modifier = Modifier.height(12.dp))

        if (sortedToday.isEmpty()) {
            Text(
                "No tasks for today",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        sortedToday.forEach { task ->
            AnimatedVisibility(
                visible = true,
                exit = fadeOut() + shrinkVertically()
            ) {
                TaskCard(
                    task = task,
                    onClick = { onEdit(task) },
                    onChecked = { onRemove(task) }
                )
            }
        }

        // 🔥 TOMORROW
        if (sortedTomorrow.isNotEmpty()) {

            Spacer(modifier = Modifier.height(28.dp))

            Text("Tomorrow", style = MaterialTheme.typography.titleMedium)

            Spacer(modifier = Modifier.height(10.dp))

            sortedTomorrow.forEach { task ->
                AnimatedVisibility(
                    visible = true,
                    exit = fadeOut() + shrinkVertically()
                ) {
                    TaskCard(
                        task = task,
                        onClick = { onEdit(task) },
                        onChecked = { onRemove(task) }
                    )
                }
            }
        }

        // 🔥 UPCOMING
        if (sortedUpcoming.isNotEmpty()) {

            Spacer(modifier = Modifier.height(28.dp))

            Text("Upcoming", style = MaterialTheme.typography.titleMedium)

            Spacer(modifier = Modifier.height(10.dp))

            sortedUpcoming.forEach { task ->
                AnimatedVisibility(
                    visible = true,
                    exit = fadeOut() + shrinkVertically()
                ) {
                    TaskCard(
                        task = task,
                        onClick = { onEdit(task) },
                        onChecked = { onRemove(task) }
                    )
                }
            }
        }
    }
}