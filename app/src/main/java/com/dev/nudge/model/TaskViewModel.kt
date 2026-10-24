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
import java.util.Date

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
    private val reminders: ReminderScheduler = AndroidReminderScheduler(context),
    private val clock: AppClock = AppSystemClock
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    val tasks: StateFlow<List<Task>> = _tasks

    private val _completedTasks = MutableStateFlow<List<Task>>(emptyList())
    val completedTasks: StateFlow<List<Task>> = _completedTasks

    // 🔥 Tasks-tab view state (survives rotation — lives as long as the activity)
    private val _selectedDate =
        MutableStateFlow(TaskUtils.todayIso(Date(clock.nowMillis())))
    val selectedDate: StateFlow<String> = _selectedDate

    private val _hideCompleted = MutableStateFlow(false)
    val hideCompleted: StateFlow<Boolean> = _hideCompleted

    /**
     * taskId → focused seconds (sum of FOCUS segments). Precomputed here so
     * item composables stay cheap with hundreds of tasks.
     * Filled by the session layer (Phase 2); empty until then.
     */
    private val _actualSecondsByTask = MutableStateFlow<Map<String, Int>>(emptyMap())
    val actualSecondsByTask: StateFlow<Map<String, Int>> = _actualSecondsByTask

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
        val completed = task.copy(
            isCompleted = true,
            completedAt = System.currentTimeMillis()
        )
        if (isLoggedIn()) {
            scope.launch { FirestoreRepository.completeTask(completed) }
        } else {
            val updatedActive = _tasks.value.filter { it.id != task.id }
            val updatedCompleted = _completedTasks.value + completed
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

    /** 🔁 Un-strike a completed task: back to active, reminder re-armed. */
    fun uncompleteTask(task: Task) {
        val active = task.copy(isCompleted = false, completedAt = null)
        if (isLoggedIn()) {
            scope.launch {
                FirestoreRepository.addTask(active)
                FirestoreRepository.deleteCompletedTask(task)
            }
        } else {
            val updatedActive = _tasks.value + active
            val updatedCompleted = _completedTasks.value.filter { it.id != task.id }
            _tasks.value = updatedActive
            _completedTasks.value = updatedCompleted
            scope.launch {
                TaskStorage.saveTasks(context, updatedActive)
                TaskStorage.saveCompletedTasks(context, updatedCompleted)
            }
        }
        reminders.scheduleIfFuture(active)
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

    /** 📅 Tasks-tab: date strip selection (state survives rotation). */
    fun selectDate(isoDate: String) {
        _selectedDate.value = isoDate
    }

    /** 🙈 Tasks-tab overflow: hide/show struck-through tasks in the list. */
    fun toggleHideCompleted() {
        _hideCompleted.value = !_hideCompleted.value
    }

    /** Called by the session layer whenever focus segments change (Phase 2). */
    fun setActualSeconds(map: Map<String, Int>) {
        _actualSecondsByTask.value = map
    }

    /**
     * 📥 Apply a restored backup (spec §9): the merged lists become the new
     * state and are persisted locally; newly restored tasks are pushed to
     * Firestore when signed in; every active future task re-arms its reminder
     * (cancel first — re-arming is idempotent).
     */
    fun applyRestored(
        mergedActive: List<Task>,
        mergedCompleted: List<Task>,
        addedActive: List<Task>,
        addedCompleted: List<Task>
    ) {
        _tasks.value = mergedActive
        _completedTasks.value = mergedCompleted

        scope.launch {
            TaskStorage.saveTasks(context, mergedActive)
            TaskStorage.saveCompletedTasks(context, mergedCompleted)
            if (isLoggedIn()) {
                addedActive.forEach { task ->
                    runCatching { FirestoreRepository.addTask(task) }
                }
                addedCompleted.forEach { task ->
                    runCatching { FirestoreRepository.restoreCompleted(task) }
                }
            }
        }

        mergedActive.forEach { task ->
            reminders.cancel(task)
            reminders.scheduleIfFuture(task)
        }
    }

    /**
     * 🧹 Wipe local guest data — including Room sessions (spec §3.3) — then
     * sign out, so the next user sees nothing.
     */
    fun logout() {
        scope.launch {
            TaskStorage.clearAll(context)
            com.dev.nudge.data.session.SessionRepository.getInstance(context).clearLocal()
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
