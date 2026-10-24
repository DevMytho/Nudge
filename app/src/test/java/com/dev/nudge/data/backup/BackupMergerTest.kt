package com.dev.nudge.data.backup

import com.dev.nudge.data.session.FocusSessionEntity
import com.dev.nudge.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupMergerTest {

    private fun task(id: String, title: String = "t", completed: Boolean = false) = Task(
        id = id,
        title = title,
        date = "2026-10-24",
        isCompleted = completed,
        completedAt = if (completed) 1L else null
    )

    private fun session(id: String) = BackupSession(
        FocusSessionEntity(id = id, date = "2026-10-24", startedAt = 1L)
    )

    @Test
    fun `union adds only missing tasks`() {
        val backup = BackupFile(tasks = listOf(task("a", "from backup"), task("b")))

        val result = BackupMerger.merge(
            localActive = listOf(task("a", "local")),
            localCompleted = emptyList(),
            localSessionIds = emptySet(),
            backup = backup
        )

        // local wins on conflict, backup-only task is added
        assertEquals(listOf("a", "b"), result.active.map { it.id })
        assertEquals("local", result.active.first { it.id == "a" }.title)
        assertEquals(listOf("b"), result.addedActive.map { it.id })
        assertEquals(2, result.active.size)
        assertTrue(result.addedCompleted.isEmpty())
    }

    @Test
    fun `backup completed tasks land in completed list`() {
        val backup = BackupFile(completedTasks = listOf(task("c", completed = true)))

        val result = BackupMerger.merge(
            localActive = emptyList(),
            localCompleted = emptyList(),
            localSessionIds = emptySet(),
            backup = backup
        )

        assertEquals(listOf("c"), result.completed.map { it.id })
        assertEquals(listOf("c"), result.addedCompleted.map { it.id })
        assertTrue(result.active.isEmpty())
    }

    @Test
    fun `task in both backup lists counts as completed`() {
        val backup = BackupFile(
            tasks = listOf(task("x")),
            completedTasks = listOf(task("x", completed = true))
        )

        val result = BackupMerger.merge(
            localActive = emptyList(),
            localCompleted = emptyList(),
            localSessionIds = emptySet(),
            backup = backup
        )

        assertEquals(listOf("x"), result.completed.map { it.id })
        assertTrue(result.active.isEmpty())
    }

    @Test
    fun `existing local task is not duplicated or overwritten`() {
        val backup = BackupFile(
            tasks = listOf(task("a", "backup version")),
            completedTasks = listOf(task("b", completed = true))
        )

        val result = BackupMerger.merge(
            localActive = listOf(task("a", "device version")),
            localCompleted = listOf(task("b", "device version", completed = true)),
            localSessionIds = emptySet(),
            backup = backup
        )

        assertEquals(1, result.active.size)
        assertEquals("device version", result.active.first().title)
        assertEquals(1, result.completed.size)
        assertTrue(result.addedActive.isEmpty())
        assertTrue(result.addedCompleted.isEmpty())
    }

    @Test
    fun `id-less backup entries are dropped`() {
        val backup = BackupFile(tasks = listOf(task(""), task("ok")))

        val result = BackupMerger.merge(
            localActive = emptyList(),
            localCompleted = emptyList(),
            localSessionIds = emptySet(),
            backup = backup
        )

        assertEquals(listOf("ok"), result.active.map { it.id })
    }

    @Test
    fun `only genuinely new sessions are counted`() {
        val backup = BackupFile(sessions = listOf(session("s1"), session("s2"), session("s3")))

        val result = BackupMerger.merge(
            localActive = emptyList(),
            localCompleted = emptyList(),
            localSessionIds = setOf("s2"),
            backup = backup
        )

        assertEquals(2, result.addedSessionCount)
    }
}