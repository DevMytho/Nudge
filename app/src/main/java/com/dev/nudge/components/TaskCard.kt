package com.dev.nudge.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dev.nudge.CardBeige
import com.dev.nudge.R
import com.dev.nudge.model.Priority
import com.dev.nudge.model.Task
import com.dev.nudge.model.TaskUtils

/**
 * 📝 Task card for the Tasks tab: title (struck through when done), deadline
 * chip, estimate chip, actual chip with progress bar, Start / overflow actions.
 *
 * All values are precomputed upstream (chips do no parsing beyond cheap
 * string formatting) so lists stay smooth with hundreds of tasks.
 */
@Composable
fun TaskCard(
    task: Task,
    actualSeconds: Int = 0,
    onClick: () -> Unit,
    onChecked: () -> Unit,
    onStart: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {

    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    val cardColor = if (isDark) MaterialTheme.colorScheme.surface else CardBeige

    var menuOpen by remember { mutableStateOf(false) }

    val estimatedMinutes = TaskUtils.estimatedMinutesEffective(task)

    Card(
        colors = CardDefaults.cardColors(containerColor = cardColor),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isDark) 2.dp else 4.dp
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clickable { onClick() }
    ) {

        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {

            // ✅ Checkbox (isolated click) — tap again to un-strike
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clickable { onChecked() }
            ) {
                Surface(
                    shape = CircleShape,
                    border = BorderStroke(
                        2.dp,
                        MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (task.isCompleted) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(2.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // ✅ CONTENT
            Column(modifier = Modifier.weight(1f)) {

                // 📝 TITLE (+ critical dot)
                Row(verticalAlignment = Alignment.CenterVertically) {

                    if (task.critical) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(MaterialTheme.colorScheme.error, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textDecoration = if (task.isCompleted) TextDecoration.LineThrough else null,
                        color = if (task.isCompleted) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                }

                // ⏱ DEADLINE CHIP (only when the task has a deadline)
                val deadlineLabel = TaskUtils.getDisplayTime(task)
                if (deadlineLabel.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.chip_due, deadlineLabel),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 🎯 ESTIMATE + ACTUAL ROW
                val hasEstimate = estimatedMinutes != null && estimatedMinutes > 0
                val hasActual = actualSeconds > 0

                if (hasEstimate || hasActual || task.priority != Priority.LOW.name) {
                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // priority label
                        Text(
                            text = task.priorityLevel.name.lowercase()
                                .replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.bodySmall,
                            color = priorityColor(task)
                        )

                        if (hasEstimate) {
                            AssistChip(
                                onClick = { },
                                label = {
                                    Text(
                                        text = if (task.estimatedMinutes == null && task.estimatedCycles != null) {
                                            stringResource(
                                                R.string.chip_estimate_cycles,
                                                task.estimatedCycles ?: 0
                                            )
                                        } else {
                                            stringResource(
                                                R.string.chip_estimate_minutes,
                                                TaskUtils.formatMinutes(estimatedMinutes ?: 0)
                                            )
                                        },
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                },
                                modifier = Modifier.height(24.dp)
                            )
                        }

                        if (hasActual) {
                            val actualMinutes = actualSeconds / 60
                            val overEstimate = hasEstimate && actualMinutes > (estimatedMinutes ?: 0)
                            val color = when {
                                overEstimate -> Amber
                                hasEstimate -> GreenOK
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Text(
                                text = stringResource(
                                    R.string.chip_actual,
                                    TaskUtils.formatMinutes(actualMinutes)
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = color
                            )
                        }
                    }

                    // 📊 thin progress bar (actual ÷ estimate)
                    if (hasEstimate && hasActual) {
                        Spacer(modifier = Modifier.height(6.dp))
                        val fraction =
                            (actualSeconds / 60f) / (estimatedMinutes ?: 1).coerceAtLeast(1)
                        LinearProgressIndicator(
                            progress = { fraction.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp),
                            color = if (fraction > 1f) Amber else GreenOK,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                }
            }

            // 🎛 ACTIONS
            Row(verticalAlignment = Alignment.CenterVertically) {

                if (onStart != null && !task.isCompleted) {
                    IconButton(onClick = onStart) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = stringResource(R.string.action_start),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                if (onEdit != null || onDelete != null) {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.action_more),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false }
                    ) {
                        if (onEdit != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_edit)) },
                                leadingIcon = { Icon(Icons.Default.Edit, null) },
                                onClick = {
                                    menuOpen = false
                                    onEdit()
                                }
                            )
                        }
                        if (onDelete != null) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.action_delete),
                                        color = MaterialTheme.colorScheme.error
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    onDelete()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun priorityColor(task: Task) = when (task.priorityLevel) {
    Priority.HIGH -> MaterialTheme.colorScheme.error
    Priority.MEDIUM -> MaterialTheme.colorScheme.tertiary
    Priority.LOW -> MaterialTheme.colorScheme.primary
}

private val Amber = androidx.compose.ui.graphics.Color(0xFFB26A00)
private val GreenOK = androidx.compose.ui.graphics.Color(0xFF2E7D32)
