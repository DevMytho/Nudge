package com.dev.nudge

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.dev.nudge.data.ThemeStorage
import com.dev.nudge.model.Task
import com.dev.nudge.model.TaskViewModel
import com.dev.nudge.model.TimerViewModel
import com.dev.nudge.screens.*
import com.dev.nudge.ui.theme.*
import com.dev.nudge.utils.NotificationHelper
import kotlinx.coroutines.launch

val CardBeige = Color(0xFFEDE3DC)

class MainActivity : ComponentActivity() {

    // 🔥 Logic lives in ViewModels (StateFlow-backed); one instance per activity
    private val taskViewModel by lazy { TaskViewModel(applicationContext) }
    private val timerViewModel by lazy {
        TimerViewModel(
            applicationContext,
            com.dev.nudge.data.session.SessionRepository.getInstance(applicationContext)
        )
    }
    private val progressViewModel by lazy {
        com.dev.nudge.model.ProgressViewModel(applicationContext)
    }

    override fun onResume() {
        super.onResume()
        // 🔁 reconcile whatever happened while the app was backgrounded
        timerViewModel.refresh()
    }

    override fun onDestroy() {
        taskViewModel.close()
        timerViewModel.close()
        progressViewModel.close()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {

        fun hasNotificationPermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED
            } else true
        }

        fun hasExactAlarmPermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                alarmManager.canScheduleExactAlarms()
            } else true
        }

        // 🔥 Notification channel
        NotificationHelper.createChannel(this)

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {

            val context = LocalContext.current
            val scope = rememberCoroutineScope()

            var isDarkMode by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                isDarkMode = ThemeStorage.loadTheme(context)
            }

            // 🔥 Logic lives in ViewModels (StateFlow-backed)
            val taskViewModel = this@MainActivity.taskViewModel
            val timerViewModel = this@MainActivity.timerViewModel

            // 🔔 One place that nudges the user for the two permissions the
            //    reminder system displays under. Scheduling itself (setAlarmClock)
            //    never needs a permission — TaskViewModel always arms the alarm.
            fun promptReminderPermissionsIfNeeded() {
                val hasNotif = hasNotificationPermission(context)
                val hasAlarm = hasExactAlarmPermission(context)
                if (hasNotif && hasAlarm) return

                Toast.makeText(
                    context,
                    getString(R.string.reminder_permissions_rationale),
                    Toast.LENGTH_LONG
                ).show()

                if (!hasNotif && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    (context as Activity).requestPermissions(
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                        1001
                    )
                }

                if (!hasAlarm && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                }
            }

            val snackbarHostState = remember { SnackbarHostState() }

            // 🗑️ Undo helper: deleting shows a snackbar; Undo brings the task
            //    back AND re-arms its reminder (TaskViewModel.restoreTask).
            fun showDeleteUndoSnackbar(task: Task) {
                scope.launch {
                    val result = snackbarHostState.showSnackbar(
                        message = getString(R.string.task_deleted),
                        actionLabel = getString(R.string.undo_action),
                        duration = SnackbarDuration.Short
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        taskViewModel.restoreTask(task)
                    }
                }
            }

            NudgeTheme(darkTheme = isDarkMode) {

                val navController = rememberNavController()
                val currentRoute =
                    navController.currentBackStackEntryAsState().value?.destination?.route

                // 🖥 Focus-tab fullscreen hides the app chrome too (bottom bar + FAB)
                var focusFullscreen by remember { mutableStateOf(false) }

                LaunchedEffect(currentRoute) {
                    if (currentRoute?.startsWith("focus") != true) focusFullscreen = false
                }

                val tasks by taskViewModel.tasks.collectAsState()

            // 📊 focused seconds per task, straight from Room sessions
            LaunchedEffect(Unit) {
                timerViewModel.focusSecondsByTask.collect { map ->
                    taskViewModel.setActualSeconds(map)
                }
            }

                Scaffold(

                    containerColor = MaterialTheme.colorScheme.background,

                    snackbarHost = { SnackbarHost(snackbarHostState) },

                    floatingActionButton = {
                        if (currentRoute != "login" &&
                            currentRoute?.startsWith("focus") != true
                        ) {
                            FloatingActionButton(
                                onClick = { navController.navigate("add") },
                                containerColor = SoftBlue,
                                contentColor = DarkText,
                                shape = CircleShape
                            ) {
                                Icon(Icons.Default.Add, null)
                            }
                        }
                    },

                    bottomBar = {
                        if (currentRoute != "login" && currentRoute?.startsWith("focus") != true) {

                            val barColor =
                                if (isDarkMode) Color.Black else MaterialTheme.colorScheme.surface

                            val selectedColor =
                                if (isDarkMode) CardBeige else Color(0xFF5AA9FF)

                            val unselectedColor =
                                if (isDarkMode) Color.Gray else MaterialTheme.colorScheme.onSurfaceVariant

                            val indicatorColor =
                                if (isDarkMode) Color(0xFF1E1E1E) else Color(0xFFDCEBFF)

                            NavigationBar(
                                containerColor = barColor,
                                tonalElevation = if (isDarkMode) 0.dp else 3.dp
                            ) {

                                NavigationBarItem(
                                    selected = currentRoute == "tasks",
                                    onClick = { navController.navigate("tasks") },
                                    icon = { Icon(Icons.Default.Home, null) },
                                    label = { Text("Tasks") },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = selectedColor,
                                        selectedTextColor = selectedColor,
                                        unselectedIconColor = unselectedColor,
                                        unselectedTextColor = unselectedColor,
                                        indicatorColor = indicatorColor
                                    )
                                )

                                NavigationBarItem(
                                    selected = currentRoute?.startsWith("focus") == true,
                                    onClick = { navController.navigate("focus") },
                                    icon = { Icon(Icons.Default.Schedule, null) },
                                    label = { Text("Focus") },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = selectedColor,
                                        selectedTextColor = selectedColor,
                                        unselectedIconColor = unselectedColor,
                                        unselectedTextColor = unselectedColor,
                                        indicatorColor = indicatorColor
                                    )
                                )

                                NavigationBarItem(
                                    selected = currentRoute?.startsWith("pomodoro") == true,
                                    onClick = { navController.navigate("pomodoro") },
                                    icon = { Icon(Icons.Default.PlayArrow, null) },
                                    label = { Text("Pomodoro") },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = selectedColor,
                                        selectedTextColor = selectedColor,
                                        unselectedIconColor = unselectedColor,
                                        unselectedTextColor = unselectedColor,
                                        indicatorColor = indicatorColor
                                    )
                                )

                                NavigationBarItem(
                                    selected = currentRoute?.startsWith("progress") == true,
                                    onClick = { navController.navigate("progress") },
                                    icon = { Icon(Icons.Default.Timeline, null) },
                                    label = { Text("Progress") },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = selectedColor,
                                        selectedTextColor = selectedColor,
                                        unselectedIconColor = unselectedColor,
                                        unselectedTextColor = unselectedColor,
                                        indicatorColor = indicatorColor
                                    )
                                )

                                NavigationBarItem(
                                    selected = currentRoute == "settings",
                                    onClick = { navController.navigate("settings") },
                                    icon = { Icon(Icons.Default.Settings, null) },
                                    label = { Text("Settings") },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = selectedColor,
                                        selectedTextColor = selectedColor,
                                        unselectedIconColor = unselectedColor,
                                        unselectedTextColor = unselectedColor,
                                        indicatorColor = indicatorColor
                                    )
                                )
                            }
                        }
                    }

                ) { innerPadding ->

                    NavHost(
                        navController = navController,
                        startDestination = "tasks",
                        modifier = Modifier.padding(innerPadding)
                    ) {

                        composable("login") {
                            LoginScreen {
                                navController.popBackStack()
                            }
                        }

                        composable("tasks") {
                            val tasksSelectedDate by taskViewModel.selectedDate.collectAsState()
                            val actualSeconds by taskViewModel.actualSecondsByTask.collectAsState()
                            val hideCompleted by taskViewModel.hideCompleted.collectAsState()

                            TasksScreen(
                                tasks = tasks,
                                selectedDate = tasksSelectedDate,
                                actualSecondsByTask = actualSeconds,
                                hideCompleted = hideCompleted,
                                onSelectDate = { taskViewModel.selectDate(it) },
                                onToggleHideCompleted = { taskViewModel.toggleHideCompleted() },
                                onToggleComplete = { task ->
                                    if (task.isCompleted) {
                                        taskViewModel.uncompleteTask(task)
                                    } else {
                                        taskViewModel.completeTask(task)
                                    }
                                },
                                onEdit = { task -> navController.navigate("edit/${task.id}") },
                                onDelete = { task ->
                                    taskViewModel.deleteTask(task)
                                    showDeleteUndoSnackbar(task)
                                },
                                onStart = { task ->
                                    navController.navigate("focus?taskId=${task.id}")
                                },
                                onUpdate = { task -> taskViewModel.updateTask(task) }
                            )
                        }

                        composable("add") {
                            val tasksSelectedDate by taskViewModel.selectedDate.collectAsState()

                            AddTaskScreen(
                                defaultDate = tasksSelectedDate,
                                onSave = { task ->
                                    // 🔔 arming the reminder lives in TaskViewModel
                                    taskViewModel.addTask(task)
                                    promptReminderPermissionsIfNeeded()
                                    navController.popBackStack()
                                },
                                onBack = { navController.popBackStack() }
                            )
                        }

                        composable("edit/{taskId}") { backStackEntry ->

                            val taskId = backStackEntry.arguments?.getString("taskId")
                            val task = tasks.find { it.id == taskId }

                            task?.let {

                                AddTaskScreen(
                                    existingTask = it,
                                    onSave = { updatedTask ->
                                        // 🔁 updateTask cancels + re-arms the reminder
                                        taskViewModel.updateTask(updatedTask)
                                        promptReminderPermissionsIfNeeded()
                                        navController.popBackStack()
                                    },
                                    onDelete = { toDelete ->
                                        taskViewModel.deleteTask(toDelete)
                                        navController.popBackStack()
                                        showDeleteUndoSnackbar(toDelete)
                                    },
                                    onBack = { navController.popBackStack() }
                                )
                            }
                        }

                        composable(
                            "focus?taskId={taskId}",
                            arguments = listOf(
                                navArgument("taskId") {
                                    type = NavType.StringType
                                    nullable = true
                                    defaultValue = null
                                }
                            )
                        ) { backStackEntry ->
                            FocusScreen(
                                tasks = tasks,
                                timer = timerViewModel,
                                initialTaskId = backStackEntry.arguments?.getString("taskId"),
                                isFullscreen = focusFullscreen,
                                onFullscreenChange = { focusFullscreen = it },
                                onDone = { task -> taskViewModel.completeTask(task) },
                                onBack = { navController.popBackStack() },
                                onGoToPomodoro = { navController.navigate("pomodoro") }
                            )
                        }

                        composable(
                            "pomodoro?taskId={taskId}",
                            arguments = listOf(
                                navArgument("taskId") {
                                    type = NavType.StringType
                                    nullable = true
                                    defaultValue = null
                                }
                            )
                        ) { backStackEntry ->
                            PomodoroScreen(
                                tasks = tasks,
                                timer = timerViewModel,
                                initialTaskId = backStackEntry.arguments?.getString("taskId"),
                                onDone = { task -> taskViewModel.completeTask(task) },
                                onGoToFocus = { navController.navigate("focus") }
                            )
                        }

                        composable("progress") {
                            ProgressScreen(
                                tasks = tasks,
                                progress = progressViewModel
                            )
                        }

                        composable("settings") {
                            SettingsScreen(
                                isDarkMode = isDarkMode,
                                onToggleTheme = {
                                    isDarkMode = it
                                    scope.launch { ThemeStorage.saveTheme(context, it) }
                                },
                                onLoginClick = {
                                    navController.navigate("login") {
                                        launchSingleTop = true
                                    }
                                },
                                onLogout = {
                                    // 🧹 wipes local guest data, then signs out
                                    taskViewModel.logout()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
