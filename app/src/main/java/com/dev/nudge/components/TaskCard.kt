package com.dev.nudge.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.dev.nudge.CardBeige
import com.dev.nudge.model.Task
import com.dev.nudge.model.TaskUtils

@Composable
fun TaskCard(
    task: Task,
    onClick: () -> Unit,
    onChecked: () -> Unit
) {

    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    val cardColor = if (isDark)
        MaterialTheme.colorScheme.surface
    else
        CardBeige

    Card(
        colors = CardDefaults.cardColors(containerColor = cardColor),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isDark) 2.dp else 4.dp
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clickable { onClick() } // 🔥 whole card clickable
    ) {

        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {

            // ✅ Checkbox (isolated click)
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clickable {
                        onChecked()
                    }
            ) {
                Surface(
                    shape = CircleShape,
                    border = BorderStroke(
                        2.dp,
                        MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.fillMaxSize()
                ) {}
            }

            Spacer(modifier = Modifier.width(12.dp))

            // ✅ CONTENT
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp) // 🔥 clean spacing
            ) {

                // 📝 TITLE
                Row(verticalAlignment = Alignment.CenterVertically) {

                    if (task.isCritical) {
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
                        maxLines = 2
                    )
                }

                // ⏱ TIME / STATUS
                Text(
                    text = getDisplayTime(task),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // 🎯 PRIORITY
                Row(verticalAlignment = Alignment.CenterVertically) {

                    val color = when (task.priority) {
                        "High" -> MaterialTheme.colorScheme.error
                        "Medium" -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.primary
                    }

                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(color, CircleShape)
                    )

                    Spacer(modifier = Modifier.width(6.dp))

                    Text(
                        text = task.priority,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

fun getDisplayTime(task: Task): String = TaskUtils.getDisplayTime(task)