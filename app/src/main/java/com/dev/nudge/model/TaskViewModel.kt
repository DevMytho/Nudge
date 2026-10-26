package com.dev.nudge.model

import android.content.Context
import android.util.Log
import com.dev.nudge.data.FirestoreRepository
import com.dev.nudge.data.SettingsStorage
import com.dev.nudge.data.TaskStorage
import com.dev.nudge.data.calendar.CalendarSync
import com.dev.nudge.widget.NudgeWidgetProvider
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

    /** 📅 bumped after each calendar reconcile — Tasks tab reloads events. */
    private val _calendarRevision = MutableStateFlow(0)
    val calendarRevision: StateFlow<Int> = _calendarRevision

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
        syncCalendar()
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
        syncCalendar()
    }

    fun completeTask(task: Task) {
        val completed = task.copy(
            isCompleted = true,
            completedAt = System.currentTimeMillis()
        )
        // ⚡ update the UI IMMEDIATELY, signed in or not — the Progress-tab
        //    streak must grow the moment the task is struck off, without
        //    waiting for a Firestore round-trip. The snapshot listener
        //    reconciles with server state as soon as it arrives.
        _tasks.value = _tasks.value.filter { it.id != task.id }
        _completedTasks.value = _completedTasks.value.filter { it.id != task.id } + completed

        if (isLoggedIn()) {
            scope.launch { FirestoreRepository.completeTask(completed) }
        } else {
            val activeNow = _tasks.value
            val completedNow = _completedTasks.value
            scope.launch {
                TaskStorage.saveTasks(context, activeNow)
                TaskStorage.saveCompletedTasks(context, completedNow)
            }
        }
        // 🔕 a completed task must never fire its reminder
        reminders.cancel(task)
        syncCalendar()
    }

    /** 🔁 Un-strike a completed task: back to active, reminder re-armed. */
    fun uncompleteTask(task: Task) {
        val active = task.copy(isCompleted = false, completedAt = null)
        // ⚡ same optimistic update as [completeTask] — instant in both modes.
        _completedTasks.value = _completedTasks.value.filter { it.id != task.id }
        _tasks.value = _tasks.value.filter { it.id != task.id } + active

        if (isLoggedIn()) {
            scope.launch {
                FirestoreRepository.addTask(active)
                FirestoreRepository.deleteCompletedTask(task)
            }
        } else {
            val activeNow = _tasks.value
            val completedNow = _completedTasks.value
            scope.launch {
                TaskStorage.saveTasks(context, activeNow)
                TaskStorage.saveCompletedTasks(context, completedNow)
            }
        }
        reminders.scheduleIfFuture(active)
        syncCalendar()
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
        syncCalendar()
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
        syncCalendar()
    }

    /**
     * 🗑 Permanent delete of a FINISHED task (Settings → Finished tasks).
     * Optimistic like the other mutations; Firestore listener reconciles.
     */
    fun deleteCompleted(task: Task) {
        _completedTasks.value = _completedTasks.value.filter { it.id != task.id }
        val remaining = _completedTasks.value
        scope.launch {
            if (isLoggedIn()) {
                runCatching { FirestoreRepository.deleteCompletedTask(task) }
            } else {
                TaskStorage.saveCompletedTasks(context, remaining)
            }
        }
        // 🏠 finished-today count on the widgets may have changed
        NudgeWidgetProvider.refresh(context)
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
     * 📅 Push the current task list to Google Calendar (when enabled).
     * Called after EVERY mutation — reconcile is idempotent and skips
     * unchanged tasks (zero HTTP), so this is cheap.
     */
    fun syncCalendar() {
        scope.launch {
            try {
                if (!SettingsStorage.load(context).calendarSyncEnabled) return@launch
                val result = CalendarSync.reconcile(context) { _tasks.value }
                if (result.status == CalendarSync.Status.OK) {
                    if (result.changed) {
                        // 🔄 events really changed → drop cached days so the
                        //    Tasks tab re-reads them (instant after fetch)
                        CalendarSync.invalidateDays()
                        _calendarRevision.value = _calendarRevision.value + 1
                    }
                    // 🏠 widgets mirror task state too
                    NudgeWidgetProvider.refresh(context)
                } else {
                    Log.w("NudgeCalendar", "background calendar sync: ${result.status}")
                }
            } catch (e: Exception) {
                Log.w("NudgeCalendar", "syncCalendar failed: ${e.message}")
            }
        }
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
        syncCalendar()
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
            // 🏠 widgets must not keep showing the signed-out user's data
            NudgeWidgetProvider.refresh(context)
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
