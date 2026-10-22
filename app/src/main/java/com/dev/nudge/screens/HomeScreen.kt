package com.dev.nudge.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dev.nudge.R
import com.dev.nudge.model.Task
import com.dev.nudge.model.TaskUtils
import com.dev.nudge.components.TaskCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    tasks: List<Task>,
    onRemove: (Task) -> Unit,
    onEdit: (Task) -> Unit,
    onDelete: (Task) -> Unit
) {

    // 🔥 GROUP (Today / Tomorrow / Upcoming) + SORT (critical first, then priority)
    val groups = TaskUtils.groupTasks(tasks)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 24.dp)
    ) {

        // 🔥 TODAY
        Text("Today", style = MaterialTheme.typography.headlineMedium)

        Spacer(modifier = Modifier.height(12.dp))

        if (groups.today.isEmpty()) {
            Text(
                "No tasks for today",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        groups.today.forEach { task ->
            SwipeToDeleteTaskCard(
                task = task,
                onEdit = onEdit,
                onComplete = onRemove,
                onDelete = onDelete
            )
        }

        // 🔥 TOMORROW
        if (groups.tomorrow.isNotEmpty()) {

            Spacer(modifier = Modifier.height(28.dp))

            Text("Tomorrow", style = MaterialTheme.typography.titleMedium)

            Spacer(modifier = Modifier.height(10.dp))

            groups.tomorrow.forEach { task ->
                SwipeToDeleteTaskCard(
                    task = task,
                    onEdit = onEdit,
                    onComplete = onRemove,
                    onDelete = onDelete
                )
            }
        }

        // 🔥 UPCOMING
        if (groups.upcoming.isNotEmpty()) {

            Spacer(modifier = Modifier.height(28.dp))

            Text("Upcoming", style = MaterialTheme.typography.titleMedium)

            Spacer(modifier = Modifier.height(10.dp))

            groups.upcoming.forEach { task ->
                SwipeToDeleteTaskCard(
                    task = task,
                    onEdit = onEdit,
                    onComplete = onRemove,
                    onDelete = onDelete
                )
            }
        }
    }
}

/**
 * 🗑 Task card with swipe-to-delete (left swipe). The actual removal — and
 * the Undo snackbar — are handled by the caller via [onDelete].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteTaskCard(
    task: Task,
    onEdit: (Task) -> Unit,
    onComplete: (Task) -> Unit,
    onDelete: (Task) -> Unit
) {

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete(task)
                // snap back — the card disappears because the task left the list
                false
            } else {
                false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(16.dp)
                    )
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.delete_task),
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    ) {
        TaskCard(
            task = task,
            onClick = { onEdit(task) },
            onChecked = { onComplete(task) }
        )
    }
}
