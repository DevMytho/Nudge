package com.dev.nudge.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.dev.nudge.model.Task
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.UUID

val Context.dataStore by preferencesDataStore("tasks")

object TaskStorage {

    private val TASKS_KEY = stringPreferencesKey("tasks_list")
    private val COMPLETED_KEY = stringPreferencesKey("completed_tasks_list")

    suspend fun saveTasks(context: Context, tasks: List<Task>) {
        val json = Json.encodeToString(tasks)
        context.dataStore.edit {
            it[TASKS_KEY] = json
        }
    }

    suspend fun loadTasks(context: Context): List<Task> {
        return readList(context, TASKS_KEY)
    }

    suspend fun saveCompletedTasks(context: Context, tasks: List<Task>) {
        val json = Json.encodeToString(tasks)
        context.dataStore.edit {
            it[COMPLETED_KEY] = json
        }
    }

    suspend fun loadCompletedTasks(context: Context): List<Task> {
        return readList(context, COMPLETED_KEY)
    }

    /** Clears both active and completed local task lists (used on logout). */
    suspend fun clearAll(context: Context) {
        context.dataStore.edit { prefs ->
            prefs.remove(TASKS_KEY)
            prefs.remove(COMPLETED_KEY)
        }
    }

    private suspend fun readList(context: Context, key: Preferences.Key<String>): List<Task> {
        return try {
            val prefs = context.dataStore.data.first()
            val json = prefs[key] ?: return emptyList()
            // 🔑 Assign a stable id to any legacy task saved without one.
            Json.decodeFromString<List<Task>>(json).map { task ->
                if (task.id.isBlank()) task.copy(id = UUID.randomUUID().toString()) else task
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
}
