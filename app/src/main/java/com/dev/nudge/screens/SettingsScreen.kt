package com.dev.nudge.screens

import android.content.Intent
import android.media.Ringtone
import android.net.Uri
import android.media.RingtoneManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.dev.nudge.BuildConfig
import com.dev.nudge.CardBeige
import com.dev.nudge.data.RingtoneStorage
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    isDarkMode: Boolean,
    onToggleTheme: (Boolean) -> Unit,
    onLoginClick: () -> Unit,
    onLogout: () -> Unit,
    onViewCompletedClick: () -> Unit // 🔥 NEW PARAM
) {

    val auth = FirebaseAuth.getInstance()
    var user by remember { mutableStateOf(auth.currentUser) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        val listener = FirebaseAuth.AuthStateListener {
            user = it.currentUser
        }

        auth.addAuthStateListener(listener)

        onDispose {
            auth.removeAuthStateListener(listener)
        }
    }

    val photoUrl = user?.photoUrl?.toString()
    val displayName = user?.displayName?.takeIf { it.isNotBlank() } ?: "Guest"
    val initial = displayName.firstOrNull()?.uppercase() ?: "G"

    // 🔊 TIMER RINGTONE
    var ringtoneUriString by remember { mutableStateOf<String?>(null) }
    var ringtoneTitle by remember { mutableStateOf("Default alarm") }
    var criticalRing by remember { mutableStateOf(false) }
    var testingRingtone by remember { mutableStateOf(false) }
    var testRingtone by remember { mutableStateOf<Ringtone?>(null) }

    suspend fun ringtoneTitleFor(uriString: String?): String {
        val uri = uriString?.let(Uri::parse)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        return RingtoneManager.getRingtone(context, uri)?.getTitle(context) ?: "Default alarm"
    }

    LaunchedEffect(Unit) {
        ringtoneUriString = RingtoneStorage.loadRingtoneUriString(context)
        ringtoneTitle = ringtoneTitleFor(ringtoneUriString)
        criticalRing = RingtoneStorage.loadCriticalRing(context)
    }

    val ringtoneLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val picked: Uri? = if (Build.VERSION.SDK_INT >= 33) {
            result.data?.getParcelableExtra(
                RingtoneManager.EXTRA_RINGTONE_PICKED_URI,
                Uri::class.java
            )
        } else {
            @Suppress("DEPRECATION")
            result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        }

        if (picked != null) {
            ringtoneUriString = picked.toString()
            scope.launch {
                RingtoneStorage.saveRingtoneUri(context, picked)
                ringtoneTitle = ringtoneTitleFor(picked.toString())
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()) // 📜 whole page scrolls
            .padding(20.dp)
    ) {

        Spacer(modifier = Modifier.height(10.dp))

        // 🔥 AVATAR + NAME
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {

            if (photoUrl != null) {
                AsyncImage(
                    model = photoUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = initial,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = displayName,
                style = MaterialTheme.typography.headlineSmall
            )
        }

        Spacer(modifier = Modifier.height(30.dp))

        // 🔥 LOGIN / LOGOUT
        Button(
            onClick = {
                if (user == null) {
                    onLoginClick()
                } else {
                    onLogout()
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (user == null) "Login / Register" else "Logout")
        }

        Spacer(modifier = Modifier.height(30.dp))

        Divider()

        Spacer(modifier = Modifier.height(20.dp))

        // 🔥 PREFERENCES
        Text(
            "Preferences",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {

            Text("Dark Mode")

            Switch(
                checked = isDarkMode,
                onCheckedChange = { onToggleTheme(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = CardBeige,
                    checkedTrackColor = androidx.compose.ui.graphics.Color(0xFF444444),
                    uncheckedThumbColor = androidx.compose.ui.graphics.Color(0xFF1E1E1E),
                    uncheckedTrackColor = androidx.compose.ui.graphics.Color(0xFF555555)
                )
            )
        }

        Spacer(modifier = Modifier.height(30.dp))

        // 🔊 SOUND
        Text(
            "Sound",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {

            Column(modifier = Modifier.weight(1f)) {
                Text("Timer ringtone")
                Text(
                    ringtoneTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            TextButton(onClick = {
                val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                    putExtra(
                        RingtoneManager.EXTRA_RINGTONE_TYPE,
                        RingtoneManager.TYPE_ALARM
                    )
                    putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                    putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                    putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Timer ringtone")
                    ringtoneUriString?.let {
                        putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(it))
                    }
                }
                ringtoneLauncher.launch(intent)
            }) {
                Text("Change")
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Ring for Critical task reminders")

            Switch(
                checked = criticalRing,
                onCheckedChange = {
                    criticalRing = it
                    scope.launch { RingtoneStorage.saveCriticalRing(context, it) }
                }
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        OutlinedButton(
            onClick = {
                if (testingRingtone) {
                    // ⏹ stop the preview
                    testRingtone?.stop()
                    testRingtone = null
                    testingRingtone = false
                } else {
                    testingRingtone = true
                    scope.launch {
                        try {
                            val uri = RingtoneStorage.resolveRingtoneUri(context)
                            RingtoneManager.getRingtone(context, uri)?.let { r ->
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                    r.isLooping = true
                                }
                                r.play()
                                testRingtone = r
                                delay(5_000) // 🔇 play briefly
                                r.stop()
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        } finally {
                            testRingtone = null
                            testingRingtone = false
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (testingRingtone) "Stop test" else "Test ringtone")
        }

        Spacer(modifier = Modifier.height(30.dp))

        Divider()

        Spacer(modifier = Modifier.height(20.dp))

        // 🔥 DATA SECTION
        Text(
            "Your Data",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onViewCompletedClick() } // 🔥 FIXED
                .padding(vertical = 12.dp)
        ) {
            Icon(Icons.Default.History, contentDescription = null)

            Spacer(modifier = Modifier.width(10.dp))

            Text("View Completed Tasks")
        }

        // 🔥 ABOUT (weight() isn't allowed inside a scrollable column)
        Spacer(modifier = Modifier.height(40.dp))

        AboutSection()
    }
}

@Composable
fun AboutSection() {

    val context = LocalContext.current

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {

        Text("Nudge v${BuildConfig.VERSION_NAME}")

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            "Built to help you stay consistent.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable {
                val intent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/devmytho")
                )
                context.startActivity(intent)
            }
        ) {

            Icon(
                imageVector = Icons.Default.Code,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.width(6.dp))

            Text(
                "Made by Devvv",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}