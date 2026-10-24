package com.dev.nudge.data.backup

import com.dev.nudge.model.Task

/**
 * 🧩 Merge policy for a restore (pure — unit tested).
 *
 * Principle: **union by id, local wins.** A restore never destroys data that
 * already exists on the device; it only adds what the backup has and the
 * device doesn't. Conflicts (same id on both sides) keep the local copy —
 * local is either newer or a deliberate local change.
 *
 * Within the backup itself: a task appearing in both lists counts as
 * completed (striking through is the later action). Entries without an id
 * are dropped — they can't be deduped, so restoring them would duplicate
 * on every restore.
 */
object BackupMerger {

    data class Result(
        /** Full lists to persist after the restore. */
        val active: List<Task>,
        val completed: List<Task>,
        /** Only the backup entries that were actually new. */
        val addedActive: List<Task>,
        val addedCompleted: List<Task>,
        val addedSessionCount: Int
    )

    fun merge(
        localActive: List<Task>,
        localCompleted: List<Task>,
        localSessionIds: Set<String>,
        backup: BackupFile
    ): Result {
        val bActive = backup.tasks.filter { it.id.isNotBlank() }
        val bCompleted = backup.completedTasks.filter { it.id.isNotBlank() }

        val localIds = (localActive + localCompleted).map { it.id }.toSet()
        val backupCompletedIds = bCompleted.map { it.id }.toSet()

        val newActive = bActive.filter {
            it.id !in localIds && it.id !in backupCompletedIds
        }
        val newCompleted = bCompleted.filter { it.id !in localIds }

        return Result(
            active = localActive + newActive,
            completed = localCompleted + newCompleted,
            addedActive = newActive,
            addedCompleted = newCompleted,
            addedSessionCount = backup.sessions.count { it.session.id !in localSessionIds }
        )
    }
}