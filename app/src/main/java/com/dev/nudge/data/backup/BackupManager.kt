package com.dev.nudge.data.backup

import android.content.Context
import com.dev.nudge.data.SettingsStorage
import com.dev.nudge.data.TaskStorage
import com.dev.nudge.data.session.SessionRepository

/**
 * 📦 Backup / restore orchestration (spec §9).
 *
 * Export reads the local mirror (source of truth for guests, kept fresh by
 * the Firestore listeners for signed-in users), so it works identically in
 * both modes. Restore is a three-way apply:
 *   1. tasks  → merged union, persisted locally (+ Firestore when signed in)
 *   2. sessions → Room insert for ids the device doesn't have (local wins)
 *   3. settings → overlay onto this device's settings
 *
 * The UI (SettingsScreen) drives this and shows a summary.
 */
object BackupManager {

    /** What a restore actually changed — shown in the result dialog. */
    data class RestoreSummary(
        val addedActive: Int,
        val addedCompleted: Int,
        val addedSessions: Int,
        val settingsApplied: Boolean
    ) {
        val totalAdded: Int get() = addedActive + addedCompleted + addedSessions
    }

    sealed interface RestoreResult {
        data class Done(val summary: RestoreSummary) : RestoreResult
        data class NewerVersion(val version: Int) : RestoreResult
        object Corrupt : RestoreResult
    }

    /** Serialize everything worth keeping into one JSON document. */
    suspend fun exportJson(context: Context): String {
        val active = TaskStorage.loadTasks(context)
        val completed = TaskStorage.loadCompletedTasks(context)
        val repo = SessionRepository.getInstance(context)
        val sessions = repo.allSessionsWithSegments()
        val settings = SettingsStorage.load(context)

        return BackupCodec.encode(
            BackupFile(
                exportedAt = System.currentTimeMillis(),
                tasks = active,
                completedTasks = completed,
                sessions = sessions.map { (session, segments) ->
                    BackupSession(session = session, segments = segments)
                },
                settings = settings.toBackupSettings()
            )
        )
    }

    /**
     * Decode + merge + persist a backup. [localActive]/[localCompleted] are
     * the current in-memory task lists (the caller owns them).
     */
    suspend fun restore(
        context: Context,
        json: String,
        localActive: List<com.dev.nudge.model.Task>,
        localCompleted: List<com.dev.nudge.model.Task>,
        applyTasks: (active: List<com.dev.nudge.model.Task>,
                     completed: List<com.dev.nudge.model.Task>,
                     addedActive: List<com.dev.nudge.model.Task>,
                     addedCompleted: List<com.dev.nudge.model.Task>) -> Unit
    ): RestoreResult {
        val backup = when (val decoded = BackupCodec.decode(json)) {
            is BackupCodec.DecodeResult.NewerVersion ->
                return RestoreResult.NewerVersion(decoded.version)
            is BackupCodec.DecodeResult.Invalid -> return RestoreResult.Corrupt
            is BackupCodec.DecodeResult.Ok -> decoded.backup
        }

        val repo = SessionRepository.getInstance(context)
        val localSessionIds = repo.allSessionIds().toSet()

        val merged = BackupMerger.merge(
            localActive = localActive,
            localCompleted = localCompleted,
            localSessionIds = localSessionIds,
            backup = backup
        )

        // 1) tasks — delegated to the caller (it owns state, listeners and
        //    reminder scheduling; here we only decide WHAT to apply)
        applyTasks(merged.active, merged.completed, merged.addedActive, merged.addedCompleted)

        // 2) sessions — insert only ids the device doesn't have
        val addedSessions = repo.restoreSessions(backup.sessions)

        // 3) settings — overlay, keeping this device's backup/account fields
        val settingsApplied = backup.settings != null && run {
            val current = SettingsStorage.load(context)
            SettingsStorage.save(context, backup.settings.applyTo(current))
            true
        }

        return RestoreResult.Done(
            RestoreSummary(
                addedActive = merged.addedActive.size,
                addedCompleted = merged.addedCompleted.size,
                addedSessions = addedSessions,
                settingsApplied = settingsApplied
            )
        )
    }

    /**
     * ⚙️ Silent auto-backup (called when the app goes to background if the
     * user enabled it). Never shows consent UI: if the grant needs user
     * interaction we skip — background flows must not pop dialogs.
     */
    suspend fun autoBackup(context: Context): Boolean {
        val settings = SettingsStorage.load(context)
        if (!settings.autoBackupEnabled) return false

        val json = exportJson(context)
        return when (val result = DriveBackupClient.upload(context, json)) {
            is DriveBackupClient.DriveOpResult.Success -> {
                SettingsStorage.save(
                    context,
                    settings.copy(
                        lastBackupAt = System.currentTimeMillis(),
                        driveAccountEmail = result.email ?: settings.driveAccountEmail
                    )
                )
                true
            }
            else -> false
        }
    }
}