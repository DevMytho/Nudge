package com.dev.nudge.screens

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dev.nudge.model.Task
import com.dev.nudge.model.TaskUtils
import com.dev.nudge.components.TaskCard

@Composable
fun HomeScreen(
    tasks: List<Task>,
    onRemove: (Task) -> Unit,
    onEdit: (Task) -> Unit
) {

    // 🔥 GROUP (Today / Tomorrow / Upcoming) + SORT (critical first, then priority)
    val groups = TaskUtils.groupTasks(tasks)

    val sortedToday = groups.today
    val sortedTomorrow = groups.tomorrow
    val sortedUpcoming = groups.upcoming

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