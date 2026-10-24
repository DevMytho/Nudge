package com.dev.nudge.data.session

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

enum class SessionMode { FOCUS, POMODORO }

enum class SegmentType { FOCUS, SHORT_BREAK, LONG_BREAK }

enum class SessionState { RUNNING, FINISHED, ABANDONED }

/**
 * 🔥 One sitting that starts when the user presses Start. Contains segments
 * (focus blocks and breaks). Actual time is derived from segments — never
 * stored redundantly on the session or the task.
 */
@Entity(
    tableName = "focus_sessions",
    indices = [Index(value = ["date"]), Index(value = ["taskId"])]
)
@Serializable
data class FocusSessionEntity(
    @PrimaryKey val id: String,
    val taskId: String? = null,
    /** Title snapshot so history still reads well after the task is deleted. */
    val taskTitleSnapshot: String = "",
    val mode: String = SessionMode.FOCUS.name,
    /** Local day of startedAt, ISO yyyy-MM-dd. */
    val date: String = "",
    val startedAt: Long = 0L,
    val endedAt: Long? = null,
    val state: String = SessionState.RUNNING.name
)

/**
 * 🔥 One focus block or break. `actualSec` is the truth: computed from
 * running intervals (resume → pause), not from the planned length.
 * Every ±5 min press adds ±300 to [extensionSec] of the current segment.
 */
@Entity(
    tableName = "session_segments",
    foreignKeys = [],
    indices = [Index(value = ["sessionId"]), Index(value = ["taskId"])]
)
@Serializable
data class SessionSegmentEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    /** denormalized for cheap per-task sums without joins */
    val taskId: String? = null,
    /** order within session */
    val index: Int = 0,
    val type: String = SegmentType.FOCUS.name,
    /** initial planned length (0 = open-ended stopwatch) */
    val plannedSec: Int = 0,
    /** net of all +5 / -5 adjustments (can be negative) */
    val extensionSec: Int = 0,
    /** real running time, pauses excluded */
    val actualSec: Int = 0,
    val startedAt: Long = 0L,
    val endedAt: Long? = null,
    /** ran to its end (vs skipped/abandoned) */
    val completed: Boolean = false
) {
    val segmentType: SegmentType
        get() = runCatching { SegmentType.valueOf(type) }.getOrDefault(SegmentType.FOCUS)
}

/** A session with its segments ordered by index (for logs and day detail). */
data class SessionWithSegments(
    val session: FocusSessionEntity,
    val segments: List<SessionSegmentEntity>
)
