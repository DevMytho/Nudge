package com.dev.nudge.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.dev.nudge.CardBeige
import com.dev.nudge.model.Task

@Composable
fun CompletedTasksScreen(
    tasks: List<Task>,
    onReAdd: (Task) -> Unit,
    onDelete: (Task) -> Unit, // 🔥 NEW
    onBack: () -> Unit
) {

    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    val textColor = if (isDark) CardBeige else Color.Black
    val accent = if (isDark) CardBeige else Color(0xFFC8D9E6)
    val cardColor = if (isDark) Color(0xFF1E1E1E) else CardBeige

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

            Text(
                "Completed Tasks",
                style = MaterialTheme.typography.headlineMedium,
                color = textColor
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        if (tasks.isEmpty()) {
            Text("No completed tasks yet 🎯", color = textColor)
            return
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {

            tasks.forEach { task ->

                Card(
                    colors = CardDefaults.cardColors(containerColor = cardColor),
                    shape = RoundedCornerShape(20.dp),
                    elevation = CardDefaults.cardElevation(
                        defaultElevation = if (isDark) 2.dp else 4.dp
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {

                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {

                        Column(modifier = Modifier.weight(1f)) {

                            Text(
                                task.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = textColor,
                                textDecoration = TextDecoration.LineThrough
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            Text(
                                "${task.date} • ${task.time}",
                                style = MaterialTheme.typography.bodySmall,
                                color = textColor.copy(alpha = 0.7f)
                            )
                        }

                        // 🔥 ACTIONS
                        Row {

                            // Re-add
                            IconButton(onClick = { onReAdd(task) }) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "Re-add",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }

                            // Delete
                            IconButton(onClick = { onDelete(task) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Delete",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}