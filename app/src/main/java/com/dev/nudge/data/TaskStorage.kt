package com.dev.nudge.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.dev.nudge.model.Task
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

val Context.dataStore by preferencesDataStore("tasks")

object TaskStorage {

    private val TASKS_KEY = stringPreferencesKey("tasks_list")

    suspend fun saveTasks(context: Context, tasks: List<Task>) {
        val json = Json.encodeToString(tasks)
        context.dataStore.edit {
            it[TASKS_KEY] = json
        }
    }

    suspend fun loadTasks(context: Context): List<Task> {
        return try {
            val prefs = context.dataStore.data.first()
            val json = prefs[TASKS_KEY] ?: return emptyList()
            Json.decodeFromString(json)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
}