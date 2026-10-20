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
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.*
import com.dev.nudge.data.ThemeStorage
import com.dev.nudge.model.TaskViewModel
import com.dev.nudge.model.TimerViewModel
import com.dev.nudge.screens.*
import com.dev.nudge.ui.theme.*
import com.dev.nudge.utils.NotificationHelper
import com.dev.nudge.utils.NotificationScheduler
import kotlinx.coroutines.launch

val CardBeige = Color(0xFFEDE3DC)

class MainActivity : ComponentActivity() {

    // 🔥 Logic lives in ViewModels (StateFlow-backed); one instance per activity
    private val taskViewModel by lazy { TaskViewModel(applicationContext) }
    private val timerViewModel by lazy { TimerViewModel(applicationContext) }

    override fun onDestroy() {
        taskViewModel.close()
        timerViewModel.close()
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

            NudgeTheme(darkTheme = isDarkMode) {

                val navController = rememberNavController()
                val currentRoute =
                    navController.currentBackStackEntryAsState().value?.destination?.route

                val tasks by taskViewModel.tasks.collectAsState()
                val completedTasks by taskViewModel.completedTasks.collectAsState()

                Scaffold(

                    containerColor = MaterialTheme.colorScheme.background,

                    floatingActionButton = {
                        if (currentRoute != "login") {
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
                        if (currentRoute != "login") {

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
                                    selected = currentRoute == "home",
                                    onClick = { navController.navigate("home") },
                                    icon = { Icon(Icons.Default.Home, null) },
                                    label = { Text("Home") },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = selectedColor,
                                        selectedTextColor = selectedColor,
                                        unselectedIconColor = unselectedColor,
                                        unselectedTextColor = unselectedColor,
                                        indicatorColor = indicatorColor
                                    )
                                )

                                NavigationBarItem(
                                    selected = currentRoute == "focus",
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
                        startDestination = "home",
                        modifier = Modifier.padding(innerPadding)
                    ) {

                        composable("login") {
                            LoginScreen {
                                navController.popBackStack()
                            }
                        }

                        composable("home") {
                            HomeScreen(
                                tasks = tasks,
                                onRemove = { task -> taskViewModel.completeTask(task) },
                                onEdit = { task -> navController.navigate("edit/${task.id}") }
                            )
                        }

                        composable("add") {
                            AddTaskScreen(
                                onSave = { task ->

                                    taskViewModel.addTask(task)

                                    // 🔥 SCHEDULE NOTIFICATION
                                    val hasNotif = hasNotificationPermission(context)
                                    val hasAlarm = hasExactAlarmPermission(context)

                                    if (!hasNotif || !hasAlarm) {

                                        Toast.makeText(
                                            context,
                                            "Enable notifications to get reminders 🔔",
                                            Toast.LENGTH_LONG
                                        ).show()

                                        // Request permissions
                                        if (!hasNotif && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            (context as Activity).requestPermissions(
                                                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                                                1001
                                            )
                                        }

                                        if (!hasAlarm && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                            context.startActivity(intent)
                                        }

                                    } else {
                                        // ✅ everything allowed → schedule normally
                                        NotificationScheduler.scheduleTask(
                                            context,
                                            task.title,
                                            task.date,
                                            task.time,
                                            task.isCritical
                                        )
                                    }

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

                                        taskViewModel.updateTask(updatedTask)

                                        val hasNotif = hasNotificationPermission(context)
                                        val hasAlarm = hasExactAlarmPermission(context)

                                        if (!hasNotif || !hasAlarm) {

                                            Toast.makeText(
                                                context,
                                                "Enable notifications to get reminders 🔔",
                                                Toast.LENGTH_LONG
                                            ).show()

                                            // Request permissions
                                            if (!hasNotif && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                (context as Activity).requestPermissions(
                                                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                                                    1001
                                                )
                                            }

                                            if (!hasAlarm && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                                context.startActivity(intent)
                                            }

                                        } else {
                                            // ✅ everything allowed → schedule normally
                                            NotificationScheduler.scheduleTask(
                                                context,
                                                updatedTask.title,
                                                updatedTask.date,
                                                updatedTask.time,
                                                updatedTask.isCritical
                                            )
                                        }

                                        navController.popBackStack()
                                    },
                                    onBack = { navController.popBackStack() }
                                )
                            }
                        }

                        composable("focus") {
                            FocusScreen(
                                tasks = tasks,
                                timer = timerViewModel,
                                onDone = { task -> taskViewModel.completeTask(task) },
                                onBack = { navController.popBackStack() }
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
                                },
                                onViewCompletedClick = {
                                    navController.navigate("completed")
                                }
                            )
                        }

                        composable("completed") {

                            CompletedTasksScreen(
                                tasks = completedTasks,
                                onReAdd = { task -> taskViewModel.reAddTask(task) },
                                onDelete = { task -> taskViewModel.deleteCompletedTask(task) },
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }
}
