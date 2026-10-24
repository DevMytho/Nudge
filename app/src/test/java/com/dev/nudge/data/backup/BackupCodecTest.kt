package com.dev.nudge.data.backup

import com.dev.nudge.data.session.FocusSessionEntity
import com.dev.nudge.data.session.SessionSegmentEntity
import com.dev.nudge.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCodecTest {

    private fun task(id: String, title: String = "t", completed: Boolean = false) = Task(
        id = id,
        title = title,
        date = "2026-10-24",
        dueTime = "10:30",
        priority = "HIGH",
        critical = true,
        estimatedMinutes = 50,
        estimatedCycles = 2,
        cycleMinutesSnapshot = 25,
        order = 7,
        isCompleted = completed,
        completedAt = if (completed) 123L else null,
        createdAt = 1000L
    )

    private fun sampleBackup() = BackupFile(
        exportedAt = 42L,
        tasks = listOf(task("a"), task("b")),
        completedTasks = listOf(task("c", completed = true)),
        sessions = listOf(
            BackupSession(
                session = FocusSessionEntity(
                    id = "s1",
                    taskId = "a",
                    taskTitleSnapshot = "write tests",
                    mode = "POMODORO",
                    date = "2026-10-24",
                    startedAt = 111L,
                    endedAt = 222L,
                    state = "FINISHED"
                ),
                segments = listOf(
                    SessionSegmentEntity(
                        id = "seg1", sessionId = "s1", taskId = "a", index = 0,
                        type = "FOCUS", plannedSec = 1500, extensionSec = 300,
                        actualSec = 1800, startedAt = 111L, endedAt = 1911L,
                        completed = true
                    )
                )
            )
        ),
        settings = BackupSettings(displayName = "Ayush", accentColor = "GREEN")
    )

    @Test
    fun `round trip preserves all payload fields`() {
        val original = sampleBackup()
        val decoded = BackupCodec.decode(BackupCodec.encode(original))

        assertTrue(decoded is BackupCodec.DecodeResult.Ok)
        val backup = (decoded as BackupCodec.DecodeResult.Ok).backup

        assertEquals(BackupCodec.CURRENT_SCHEMA_VERSION, backup.schemaVersion)
        assertEquals(original.exportedAt, backup.exportedAt)
        assertEquals(original.tasks, backup.tasks)
        assertEquals(original.completedTasks, backup.completedTasks)
        assertEquals(original.sessions, backup.sessions)
        assertEquals(original.settings, backup.settings)
    }

    @Test
    fun `encode stamps current schema version`() {
        val encoded = BackupCodec.encode(sampleBackup().copy(schemaVersion = 99))
        val decoded = BackupCodec.decode(encoded)
        assertTrue(decoded is BackupCodec.DecodeResult.Ok)
    }

    @Test
    fun `newer schema version is rejected`() {
        val json = """{"schemaVersion": ${BackupCodec.CURRENT_SCHEMA_VERSION + 1}, "app":"nudge"}"""
        val decoded = BackupCodec.decode(json)
        assertTrue(decoded is BackupCodec.DecodeResult.NewerVersion)
        assertEquals(
            BackupCodec.CURRENT_SCHEMA_VERSION + 1,
            (decoded as BackupCodec.DecodeResult.NewerVersion).version
        )
    }

    @Test
    fun `garbage input is invalid not a crash`() {
        assertTrue(BackupCodec.decode("not json at all") is BackupCodec.DecodeResult.Invalid)
        assertTrue(BackupCodec.decode("") is BackupCodec.DecodeResult.Invalid)
        // `{}` is a valid EMPTY backup (all defaults) — still not corrupt
        val empty = BackupCodec.decode("{}")
        assertTrue(empty is BackupCodec.DecodeResult.Ok)
        assertTrue((empty as BackupCodec.DecodeResult.Ok).backup.tasks.isEmpty())
    }

    @Test
    fun `unknown fields from a newer writer are ignored`() {
        val json = """
            {"schemaVersion": ${BackupCodec.CURRENT_SCHEMA_VERSION},
             "app":"nudge",
             "someFutureField": {"nested": true},
             "tasks": [{"id":"x","title":"hi","date":"2026-01-01"}]}
        """.trimIndent()
        val decoded = BackupCodec.decode(json)
        assertTrue(decoded is BackupCodec.DecodeResult.Ok)
        assertEquals(listOf("x"), (decoded as BackupCodec.DecodeResult.Ok).backup.tasks.map { it.id })
    }

    @Test
    fun `settings overlay keeps device-specific fields`() {
        val backup = sampleBackup().settings!!
        val restored = backup.applyTo(
            com.dev.nudge.data.UserSettings(
                lastBackupAt = 999L,
                autoBackupEnabled = true,
                driveAccountEmail = "device@local"
            )
        )
        assertEquals("Ayush", restored.displayName)
        assertEquals(com.dev.nudge.data.AccentColor.GREEN, restored.accentColor)
        assertEquals(999L, restored.lastBackupAt)
        assertTrue(restored.autoBackupEnabled)
        assertEquals("device@local", restored.driveAccountEmail)
    }

    @Test
    fun `settings overlay clamps out-of-range values`() {
        val restored = BackupSettings(
            focusMinutes = 9999,
            shortBreakMinutes = -5,
            cyclesBeforeLongBreak = 0,
            dailyGoalMinutes = 3
        ).applyTo(com.dev.nudge.data.UserSettings())
        assertEquals(240, restored.focusMinutes)
        assertEquals(1, restored.shortBreakMinutes)
        assertEquals(1, restored.cyclesBeforeLongBreak)
        assertEquals(5, restored.dailyGoalMinutes)
    }
}