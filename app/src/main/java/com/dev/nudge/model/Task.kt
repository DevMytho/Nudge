package com.dev.nudge.model
import com.google.firebase.firestore.IgnoreExtraProperties
import kotlinx.serialization.Serializable
import java.util.UUID

@IgnoreExtraProperties
@Serializable
data class Task(
    val id: String = "",
    val title: String = "",
    val time: String = "",
    val priority: String = "",
    val isCritical: Boolean = false,
    val date: String = "",
    val isCompleted: Boolean = false
)