package com.dev.nudge.data.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 🔬 Encode/decode the backup JSON (pure — no Android types, unit tested).
 *
 * Forward compatibility: unknown fields from a newer app are ignored, but a
 * file whose [BackupFile.schemaVersion] is newer than this build is REJECTED
 * instead of silently dropping data this build doesn't understand.
 */
object BackupCodec {

    const val CURRENT_SCHEMA_VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    sealed interface DecodeResult {
        data class Ok(val backup: BackupFile) : DecodeResult
        /** File from a newer app version — tell the user to update. */
        data class NewerVersion(val version: Int) : DecodeResult
        /** Not a Nudge backup / truncated / corrupt. */
        data class Invalid(val reason: String) : DecodeResult
    }

    fun encode(backup: BackupFile): String =
        json.encodeToString(backup.copy(schemaVersion = CURRENT_SCHEMA_VERSION))

    fun decode(raw: String): DecodeResult {
        if (raw.isBlank()) return DecodeResult.Invalid("empty")
        val parsed = try {
            json.decodeFromString<BackupFile>(raw)
        } catch (e: Exception) {
            return DecodeResult.Invalid(e.message ?: "malformed json")
        }
        if (parsed.schemaVersion > CURRENT_SCHEMA_VERSION) {
            return DecodeResult.NewerVersion(parsed.schemaVersion)
        }
        if (parsed.schemaVersion < 1) return DecodeResult.Invalid("bad version")
        return DecodeResult.Ok(parsed)
    }
}