package com.dev.nudge.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.dev.nudge.CardBeige
import com.google.firebase.auth.FirebaseAuth

@Composable
fun SettingsScreen(
    isDarkMode: Boolean,
    onToggleTheme: (Boolean) -> Unit,
    onLoginClick: () -> Unit,
    onViewCompletedClick: () -> Unit // 🔥 NEW PARAM
) {

    val auth = FirebaseAuth.getInstance()
    var user by remember { mutableStateOf(auth.currentUser) }

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

    Column(
        modifier = Modifier
            .fillMaxSize()
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
                    auth.signOut()
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

        Spacer(modifier = Modifier.weight(1f))

        // 🔥 ABOUT
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

        Text("Nudge v1.0")

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