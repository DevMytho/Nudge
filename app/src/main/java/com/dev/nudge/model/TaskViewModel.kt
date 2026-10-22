package com.dev.nudge.model

import android.content.Context
import com.dev.nudge.data.FirestoreRepository
import com.dev.nudge.data.TaskStorage
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 🔥 Owns all task state.
 *
 * - Logged in → Firestore snapshot listeners keep [tasks]/[completedTasks] fresh.
 * - Guest     → tasks live in DataStore ([TaskStorage]).
 * - Logout    → local data is wiped before signing out.
 *
 * Reminder lifecycle lives here too (single source of truth):
 * add/update re-arm, complete/delete cancel, restore/undo re-arms.
 *
 * Plain class (no lifecycle-viewmodel dep) — instantiate with remember {}.
 */
class TaskViewModel(
    private val context: Context,
    private val reminders: ReminderScheduler = AndroidReminderScheduler(context)
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    val tasks: StateFlow<List<Task>> = _tasks

    private val _completedTasks = MutableStateFlow<List<Task>>(emptyList())
    val completedTasks: StateFlow<List<Task>> = _completedTasks

    private var tasksListener: ListenerRegistration? = null
    private var completedListener: ListenerRegistration? = null

    init {
        FirebaseAuth.getInstance().addAuthStateListener { auth ->
            onUserChanged(auth.currentUser != null)
        }
    }

    private fun onUserChanged(loggedIn: Boolean) {
        tasksListener?.remove()
        completedListener?.remove()
        tasksListener = null
        completedListener = null

        if (loggedIn) {
            tasksListener = FirestoreRepository.listenTasks {
                _tasks.value = it
                // 💾 mirror to local storage so BootReceiver can reschedule
                //    reminders after a reboot even without network
                scope.launch { TaskStorage.saveTasks(context, it) }
            }
            completedListener = FirestoreRepository.listenCompletedTasks {
                _completedTasks.value = it
                scope.launch { TaskStorage.saveCompletedTasks(context, it) }
            }
        } else {
            scope.launch {
                _tasks.value = TaskStorage.loadTasks(context)
                _completedTasks.value = TaskStorage.loadCompletedTasks(context)
            }
        }
    }

    private fun isLoggedIn(): Boolean = FirebaseAuth.getInstance().currentUser != null

    // ------------------------------------------------------------- actions

    fun addTask(task: Task) {
        if (isLoggedIn()) {
            scope.launch { FirestoreRepository.addTask(task) }
        } else {
            val updated = _tasks.value + task
            _tasks.value = updated
            scope.launch { TaskStorage.saveTasks(context, updated) }
        }
        reminders.scheduleIfFuture(task)
    }

    fun updateTask(task: Task) {
        if (isLoggedIn()) {
            scope.launch { FirestoreRepository.updateTask(task) }
        } else {
            val updated = _tasks.value.map { if (it.id == task.id) task else it }
            _tasks.value = updated
            scope.launch { TaskStorage.saveTasks(context, updated) }
        }
        // 🔁 edit = cancel the old reminder, then re-arm for the new date/time
        reminders.cancel(task)
        reminders.scheduleIfFuture(task)
    }

    fun completeTask(task: Task) {
        if (isLoggedIn()) {
            scope.launch { FirestoreRepository.completeTask(task) }
        } else {
            val updatedActive = _tasks.value.filter { it.id != task.id }
            val updatedCompleted = _completedTasks.value + task
            _tasks.value = updatedActive
            _completedTasks.value = updatedCompleted
            scope.launch {
                TaskStorage.saveTasks(context, updatedActive)
                TaskStorage.saveCompletedTasks(context, updatedCompleted)
            }
        }
        // 🔕 a completed task must never fire its reminder
        reminders.cancel(task)
    }

    /** 🗑 Permanently remove an active task (used by swipe/delete + undo). */
    fun deleteTask(task: Task) {
        if (isLoggedIn()) {
            scope.launch { FirestoreRepository.deleteTask(task) }
        } else {
            val updated = _tasks.value.filter { it.id != task.id }
            _tasks.value = updated
            scope.launch { TaskStorage.saveTasks(context, updated) }
        }
        reminders.cancel(task)
    }

    /** ↩️ Undo a delete: bring the task back and re-arm its reminder. */
    fun restoreTask(task: Task) {
        if (isLoggedIn()) {
            scope.launch { FirestoreRepository.addTask(task) }
        } else {
            val updated = _tasks.value + task
            _tasks.value = updated
            scope.launch { TaskStorage.saveTasks(context, updated) }
        }
        reminders.scheduleIfFuture(task)
    }

    /** Restore a completed task back to the active list. */
    fun reAddTask(task: Task) {
        if (isLoggedIn()) {
            scope.launch {
                FirestoreRepository.addTask(task)
                FirestoreRepository.deleteCompletedTask(task)
            }
        } else {
            val updatedActive = _tasks.value + task
            val updatedCompleted = _completedTasks.value.filter { it.id != task.id }
            _tasks.value = updatedActive
            _completedTasks.value = updatedCompleted
            scope.launch {
                TaskStorage.saveTasks(context, updatedActive)
                TaskStorage.saveCompletedTasks(context, updatedCompleted)
            }
        }
        reminders.scheduleIfFuture(task)
    }

    fun deleteCompletedTask(task: Task) {
        if (isLoggedIn()) {
            scope.launch { FirestoreRepository.deleteCompletedTask(task) }
        } else {
            val updated = _completedTasks.value.filter { it.id != task.id }
            _completedTasks.value = updated
            scope.launch { TaskStorage.saveCompletedTasks(context, updated) }
        }
    }

    /** 🧹 Wipe local guest data, then sign out (so the next user sees nothing). */
    fun logout() {
        scope.launch {
            TaskStorage.clearAll(context)
            _tasks.value = emptyList()
            _completedTasks.value = emptyList()
            FirebaseAuth.getInstance().signOut()
        }
    }

    /** Detach listeners and stop coroutines (called when the activity dies). */
    fun close() {
        tasksListener?.remove()
        completedListener?.remove()
        tasksListener = null
        completedListener = null
        scope.cancel()
    }
}
