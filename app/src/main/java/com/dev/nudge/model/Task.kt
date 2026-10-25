package com.dev.nudge.model

import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.PropertyName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

/**
 * 🔥 Priority is typed in code. It is stored as a plain string
 * ([Priority.name]) because Firestore's reflective enum mapping throws on
 * legacy capitalized values ("High") that exist in already-shipped data.
 * [Priority.fromStorage] tolerates those legacy values.
 */
enum class Priority {
    LOW, MEDIUM, HIGH;

    /** Sort weight: HIGH first (1), then MEDIUM (2), LOW (3). */
    val sortWeight: Int
        get() = when (this) {
            HIGH -> 1
            MEDIUM -> 2
            LOW -> 3
        }

    companion object {
        /** Tolerates legacy capitalized values ("High") from pre-v3 data. */
        fun fromStorage(raw: String?): Priority = when (raw?.trim()?.uppercase()) {
            "HIGH" -> HIGH
            "MEDIUM" -> MEDIUM
            "LOW" -> LOW
            else -> LOW
        }
    }
}

@IgnoreExtraProperties
@Serializable
data class Task(
    val id: String = "",
    val title: String = "",
    /** Planned day, ISO yyyy-MM-dd (local zone). Required; defaults to today. */
    val date: String = "",
    /** Optional HH:mm deadline on [date]. Drives reminders/overdue labels only. */
    val dueTime: String? = null,
    /** Storage form of [priorityLevel]: a [Priority] name. */
    val priority: String = Priority.LOW.name,
    val critical: Boolean = false,
    /** Canonical estimate in minutes; null = no estimate. */
    val estimatedMinutes: Int? = null,
    /** Set only when the user entered the estimate as pomodoro cycles. */
    val estimatedCycles: Int? = null,
    /** focusMinutes at the time of entry, so settings edits don't rewrite history. */
    val cycleMinutesSnapshot: Int? = null,
    /** Stable creation order within a day (minutes since epoch). */
    val order: Int = 0,
    val isCompleted: Boolean = false,
    val completedAt: Long? = null,
    val createdAt: Long = 0L,

    // ---- legacy (pre-v3) fields kept so old local JSON / Firestore docs
    // ---- still deserialize; migrated by [normalized] on every read.
    // ----
    // ---- ⚠️ The legacy flag must NOT be named `isCritical`: Kotlin then
    // ---- generates isCritical() which Firestore resolves to the same bean
    // ---- property as getCritical() → "Found conflicting getters for name
    // ---- isCritical" and every read/write of Task throws. The property is
    // ---- renamed instead; the wire/storage names stay "isCritical" via
    // ---- @PropertyName (Firestore) and @SerialName (local JSON).
    val time: String? = null,
    @field:PropertyName("isCritical")
    @get:PropertyName("isCritical")
    @SerialName("isCritical")
    val legacyIsCritical: Boolean = false
) {
    /** Typed priority view; tolerates legacy "High"/"Medium"/"Low". */
    val priorityLevel: Priority
        get() = Priority.fromStorage(priority)
}

/**
 * 🔄 One-time (idempotent) migration applied on every read boundary:
 *  - dd-MM-yyyy dates → ISO yyyy-MM-dd
 *  - legacy `time` → `dueTime`
 *  - legacy `isCritical` (stored as [Task.legacyIsCritical]) → `critical`
 *  - legacy capitalized priority → [Priority] name
 *  - legacy tasks without `order` get one derived from `createdAt`
 */
fun Task.normalized(): Task {
    val migratedOrder = when {
        order != 0 -> order
        createdAt > 0L -> (createdAt / 60_000L).toInt()
        else -> 0
    }
    return copy(
        date = TaskUtils.normalizeLegacyDate(date),
        dueTime = dueTime ?: time?.takeIf { it.isNotBlank() },
        priority = priorityLevel.name,
        critical = critical || legacyIsCritical,
        legacyIsCritical = false,
        time = null,
        order = migratedOrder
    )
}
