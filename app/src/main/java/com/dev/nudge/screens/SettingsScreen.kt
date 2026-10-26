package com.dev.nudge.screens

import android.app.PendingIntent
import android.content.Intent
import android.media.Ringtone
import android.net.Uri
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.dev.nudge.BuildConfig
import com.dev.nudge.CardBeige
import com.dev.nudge.R
import com.dev.nudge.data.AccentColor
import com.dev.nudge.data.RingtoneStorage
import com.dev.nudge.data.ThemeMode
import com.dev.nudge.data.UserSettings
import com.dev.nudge.data.WeekStart
import com.dev.nudge.data.backup.BackupManager
import com.dev.nudge.data.backup.DriveBackupClient
import com.dev.nudge.data.calendar.CalendarClient
import com.dev.nudge.ui.theme.DarkText
import com.dev.nudge.ui.theme.accentSwatch
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "NudgeDrive"

/** Which Drive operation to resume after the user grants consent. */
private sealed interface PendingDriveOp {
    /** Bundle-safe id — rememberSaveable can't store the object itself. */
    val key: String
    data object Upload : PendingDriveOp { override val key: String = "upload" }
    data object Download : PendingDriveOp { override val key: String = "download" }
}

@Composable
fun SettingsScreen(
    settings: UserSettings,
    onSettingsChange: (UserSettings) -> Unit,
    onLoginClick: () -> Unit,
    onLogout: () -> Unit,
    onReplayTutorial: () -> Unit,
    onOpenFinished: () -> Unit,
    onCalendarEnabled: () -> Unit,
    onRestore: suspend (String) -> BackupManager.RestoreResult
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
        onDispose { auth.removeAuthStateListener(listener) }
    }

    val photoUrl = user?.photoUrl?.toString()
    val accountName = user?.displayName?.takeIf { it.isNotBlank() } ?: "Guest"
    val shownName = settings.displayName?.takeIf { it.isNotBlank() } ?: accountName
    val initial = shownName.firstOrNull()?.uppercase() ?: "G"

    // ---------------------------------------------------------- Drive state
    // 🔑 rememberSaveable: Android can recreate MainActivity while the Google
    //    consent screen is open — a plain `remember` dropped the pending op
    //    there, so the result arrived with nothing to resume and the backup
    //    silently did nothing (spinner stuck). Names are Bundle-safe.
    var pendingDriveOpName by rememberSaveable { mutableStateOf<String?>(null) }
    var resolveIntent by rememberSaveable { mutableStateOf<PendingIntent?>(null) }

    fun opFromName(name: String?): PendingDriveOp? = when (name) {
        PendingDriveOp.Upload.key -> PendingDriveOp.Upload
        PendingDriveOp.Download.key -> PendingDriveOp.Download
        else -> null
    }

    var driveBusy by remember { mutableStateOf(false) }
    var driveStatus by remember { mutableStateOf<String?>(null) }
    var restoreResult by remember { mutableStateOf<BackupManager.RestoreResult?>(null) }
    // 🆕 What's-new changelog (Settings → Tutorial → What's new)
    var showWhatsNew by rememberSaveable { mutableStateOf(false) }

    fun driveErrorText(kind: DriveBackupClient.DriveErrorKind): String =
        when (kind) {
            DriveBackupClient.DriveErrorKind.OFFLINE ->
                context.getString(R.string.backup_err_offline)
            DriveBackupClient.DriveErrorKind.AUTH ->
                context.getString(R.string.backup_err_auth)
            DriveBackupClient.DriveErrorKind.QUOTA ->
                context.getString(R.string.backup_err_quota)
            DriveBackupClient.DriveErrorKind.NOT_FOUND ->
                context.getString(R.string.backup_err_none)
            DriveBackupClient.DriveErrorKind.TRANSIENT ->
                context.getString(R.string.backup_err_transient)
            DriveBackupClient.DriveErrorKind.UNKNOWN ->
                context.getString(R.string.backup_err_generic)
        }

    fun runDriveOp(op: PendingDriveOp) {
        scope.launch {
            driveBusy = true
            driveStatus = null
            val result = when (op) {
                PendingDriveOp.Upload ->
                    DriveBackupClient.upload(context, BackupManager.exportJson(context))
                PendingDriveOp.Download ->
                    DriveBackupClient.download(context)
            }
            when (result) {
                is DriveBackupClient.DriveOpResult.NeedsAuth -> {
                    // consent dialog → resume the same op from the launcher
                    pendingDriveOpName = op.key
                    resolveIntent = result.pendingIntent
                    return@launch
                }
                is DriveBackupClient.DriveOpResult.Error -> {
                    Log.w(TAG, "drive $op failed: ${result.kind} — ${result.detail.take(300)}")
                    driveBusy = false
                    // Show the mapped message AND the raw reason (truncated),
                    // so a console-side problem is visible without logcat.
                    driveStatus = driveErrorText(result.kind) +
                        if (result.detail.isBlank()) "" else "\n(${result.detail.take(160)})"
                }
                is DriveBackupClient.DriveOpResult.Success -> {
                    Log.d(TAG, "drive $op ok (email=${result.email ?: "-"})")
                    driveBusy = false
                    when (op) {
                        PendingDriveOp.Upload -> {
                            driveStatus =
                                context.getString(R.string.backup_upload_ok)
                            onSettingsChange(
                                settings.copy(
                                    lastBackupAt = System.currentTimeMillis(),
                                    driveAccountEmail = result.email
                                        ?: settings.driveAccountEmail
                                )
                            )
                        }
                        PendingDriveOp.Download -> {
                            val body = result.body
                            if (body.isNullOrBlank()) {
                                driveStatus =
                                    context.getString(R.string.backup_err_corrupt)
                            } else {
                                driveBusy = true
                                restoreResult = onRestore(body)
                                driveBusy = false
                            }
                        }
                    }
                }
            }
        }
    }

    val authLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        Log.d(TAG, "consent returned resultCode=${result.resultCode}")
        val auth = DriveBackupClient.authFromIntent(context, result.data)
        when (auth) {
            is DriveBackupClient.DriveAuth.Authorized -> {
                val op = opFromName(pendingDriveOpName)
                pendingDriveOpName = null
                if (op != null) runDriveOp(op) else driveBusy = false
            }
            is DriveBackupClient.DriveAuth.Failed -> {
                Log.w(TAG, "consent failed: ${auth.reason}")
                pendingDriveOpName = null
                driveBusy = false
                driveStatus = context.getString(R.string.backup_err_auth)
            }
            is DriveBackupClient.DriveAuth.NeedsResolution -> {
                // ⚠️ keep pendingDriveOpName — a second hop must still resume it
                resolveIntent = auth.pendingIntent
            }
        }
    }

    // Drive consent resolution must happen outside composition
    LaunchedEffect(resolveIntent) {
        resolveIntent?.let { pi ->
            resolveIntent = null
            try {
                authLauncher.launch(IntentSenderRequest.Builder(pi).build())
            } catch (e: Exception) {
                Log.w(TAG, "consent launch failed: ${e.message}")
                pendingDriveOpName = null
                driveBusy = false
                driveStatus = context.getString(R.string.backup_err_auth)
            }
        }
    }

    // ------------------------------------------------------- Calendar state
    var calendarStatus by remember { mutableStateOf<String?>(null) }
    var calendarConsentPending by rememberSaveable { mutableStateOf(false) }
    var calResolveIntent by remember { mutableStateOf<PendingIntent?>(null) }

    /** Ask for the calendar scope; flips the setting on once granted. */
    fun enableCalendar() {
        scope.launch {
            calendarStatus = null
            when (val auth = CalendarClient.authorize(context)) {
                is CalendarClient.CalAuth.Authorized -> {
                    onSettingsChange(settings.copy(calendarSyncEnabled = true))
                    onCalendarEnabled()
                }
                is CalendarClient.CalAuth.NeedsResolution -> {
                    calendarConsentPending = true
                    calResolveIntent = auth.pendingIntent
                }
                is CalendarClient.CalAuth.Failed -> {
                    calendarConsentPending = false
                    calendarStatus = auth.reason
                }
            }
        }
    }

    val calendarAuthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val auth = CalendarClient.authFromIntent(context, result.data)
        when (auth) {
            is CalendarClient.CalAuth.Authorized -> {
                calendarConsentPending = false
                onSettingsChange(settings.copy(calendarSyncEnabled = true))
                onCalendarEnabled()
            }
            is CalendarClient.CalAuth.Failed -> {
                calendarConsentPending = false
                calendarStatus = context.getString(R.string.calendar_consent_error)
            }
            is CalendarClient.CalAuth.NeedsResolution -> {
                calResolveIntent = auth.pendingIntent
            }
        }
    }

    LaunchedEffect(calResolveIntent) {
        calResolveIntent?.let { pi ->
            calResolveIntent = null
            try {
                calendarAuthLauncher.launch(IntentSenderRequest.Builder(pi).build())
            } catch (e: Exception) {
                Log.w(TAG, "calendar consent launch failed: ${e.message}")
                calendarConsentPending = false
                calendarStatus = context.getString(R.string.calendar_consent_error)
            }
        }
    }

    // -------------------------------------------------------- file export/import
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val json = BackupManager.exportJson(context)
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(json.toByteArray(Charsets.UTF_8))
                    }
                    driveStatus = context.getString(R.string.backup_export_ok)
                } catch (e: Exception) {
                    driveStatus = context.getString(R.string.backup_err_generic)
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val json = context.contentResolver
                        .openInputStream(uri)
                        ?.bufferedReader()
                        ?.use { it.readText() }
                    restoreResult = if (json.isNullOrBlank()) {
                        BackupManager.RestoreResult.Corrupt
                    } else {
                        onRestore(json)
                    }
                } catch (e: Exception) {
                    restoreResult = BackupManager.RestoreResult.Corrupt
                }
            }
        }
    }

    // 🔊 TIMER RINGTONE
    var ringtoneUriString by remember { mutableStateOf<String?>(null) }
    var ringtoneTitle by remember { mutableStateOf("") }
    var criticalRing by remember { mutableStateOf(false) }
    var testingRingtone by remember { mutableStateOf(false) }
    var testRingtone by remember { mutableStateOf<Ringtone?>(null) }

    suspend fun ringtoneTitleFor(uriString: String?): String {
        val uri = uriString?.let(Uri::parse)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        return RingtoneManager.getRingtone(context, uri)?.getTitle(context)
            ?: context.getString(R.string.settings_default_alarm)
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
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {

        Spacer(Modifier.height(10.dp))

        // ================================================== PROFILE
        Text(
            stringResource(R.string.settings_profile),
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(Modifier.height(14.dp))

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

            Spacer(Modifier.height(14.dp))

            OutlinedTextField(
                value = settings.displayName ?: "",
                onValueChange = { text ->
                    onSettingsChange(
                        settings.copy(displayName = text.ifBlank { null })
                    )
                },
                label = { Text(stringResource(R.string.settings_display_name)) },
                placeholder = { Text(accountName) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = stringResource(R.string.settings_display_name_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(24.dp))

        // ================================================ ACCOUNT
        Button(
            onClick = {
                if (user == null) onLoginClick() else onLogout()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                stringResource(
                    if (user == null) R.string.settings_login
                    else R.string.settings_logout
                )
            )
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        // ============================================== APPEARANCE
        Text(
            stringResource(R.string.settings_appearance),
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(Modifier.height(12.dp))

        Text(
            stringResource(R.string.settings_theme_mode),
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(Modifier.height(8.dp))

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ThemeMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = settings.themeMode == mode,
                    onClick = { onSettingsChange(settings.copy(themeMode = mode)) },
                    shape = SegmentedButtonDefaults.itemShape(
                        count = ThemeMode.entries.size,
                        index = index
                    )
                ) {
                    Text(
                        stringResource(
                            when (mode) {
                                ThemeMode.SYSTEM -> R.string.settings_theme_system
                                ThemeMode.LIGHT -> R.string.settings_theme_light
                                ThemeMode.DARK -> R.string.settings_theme_dark
                            }
                        )
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            stringResource(R.string.settings_accent),
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(Modifier.height(10.dp))

        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            AccentColor.entries.forEach { accent ->
                val selected = settings.accentColor == accent
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(accentSwatch(accent))
                        .border(
                            width = if (selected) 3.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.onSurface
                            else Color.LightGray,
                            shape = CircleShape
                        )
                        .clickable {
                            onSettingsChange(settings.copy(accentColor = accent))
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = DarkText
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        // ========================================== FOCUS DEFAULTS
        Text(
            stringResource(R.string.settings_focus_defaults),
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(Modifier.height(8.dp))

        StepperRow(
            label = stringResource(R.string.settings_focus_minutes),
            value = settings.focusMinutes,
            range = 5..120,
            step = 5,
            unitRes = R.string.settings_unit_min
        ) { new ->
            onSettingsChange(settings.copy(focusMinutes = new))
        }

        StepperRow(
            label = stringResource(R.string.settings_short_break),
            value = settings.shortBreakMinutes,
            range = 5..60,
            step = 5,
            unitRes = R.string.settings_unit_min
        ) { new ->
            onSettingsChange(settings.copy(shortBreakMinutes = new))
        }

        StepperRow(
            label = stringResource(R.string.settings_long_break),
            value = settings.longBreakMinutes,
            range = 5..60,
            step = 5,
            unitRes = R.string.settings_unit_min
        ) { new ->
            onSettingsChange(settings.copy(longBreakMinutes = new))
        }

        StepperRow(
            label = stringResource(R.string.settings_cycles_before_long),
            value = settings.cyclesBeforeLongBreak,
            range = 1..12,
            step = 1,
            unitRes = R.string.settings_unit_blocks
        ) { new ->
            onSettingsChange(settings.copy(cyclesBeforeLongBreak = new))
        }

        SwitchRow(
            label = stringResource(R.string.settings_auto_start),
            checked = settings.autoStartNextPhase
        ) { new ->
            onSettingsChange(settings.copy(autoStartNextPhase = new))
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        // ======================================== GOALS & BEHAVIOR
        Text(
            stringResource(R.string.settings_goals_behavior),
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(Modifier.height(8.dp))

        StepperRow(
            label = stringResource(R.string.settings_daily_goal),
            value = settings.dailyGoalMinutes,
            range = 15..480,
            step = 15,
            unitRes = R.string.settings_unit_min
        ) { new ->
            onSettingsChange(settings.copy(dailyGoalMinutes = new))
        }

        StepperRow(
            label = stringResource(R.string.settings_streak_min),
            value = settings.streakMinMinutes,
            range = 5..120,
            step = 5,
            unitRes = R.string.settings_unit_min
        ) { new ->
            onSettingsChange(settings.copy(streakMinMinutes = new))
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                stringResource(R.string.settings_week_start),
                Modifier.weight(1f)
            )
            SingleChoiceSegmentedButtonRow {
                WeekStart.entries.forEachIndexed { index, weekStart ->
                    SegmentedButton(
                        selected = settings.weekStartsOn == weekStart,
                        onClick = {
                            onSettingsChange(settings.copy(weekStartsOn = weekStart))
                        },
                        shape = SegmentedButtonDefaults.itemShape(
                            count = WeekStart.entries.size,
                            index = index
                        )
                    ) {
                        Text(weekStart.name)
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        SwitchRow(
            label = stringResource(R.string.settings_haptics),
            checked = settings.hapticsEnabled
        ) { new ->
            onSettingsChange(settings.copy(hapticsEnabled = new))
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        // ================================================== SOUND
        Text(
            stringResource(R.string.settings_sound),
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(Modifier.height(10.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_timer_ringtone))
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
                    putExtra(
                        RingtoneManager.EXTRA_RINGTONE_TITLE,
                        context.getString(R.string.settings_timer_ringtone)
                    )
                    ringtoneUriString?.let {
                        putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(it))
                    }
                }
                ringtoneLauncher.launch(intent)
            }) {
                Text(stringResource(R.string.settings_change))
            }
        }

        SwitchRow(
            label = stringResource(R.string.settings_ring_critical),
            checked = criticalRing
        ) { new ->
            criticalRing = new
            scope.launch { RingtoneStorage.saveCriticalRing(context, new) }
        }

        Spacer(Modifier.height(4.dp))

        OutlinedButton(
            onClick = {
                if (testingRingtone) {
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
                                delay(5_000)
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
            Text(
                stringResource(
                    if (testingRingtone) R.string.settings_stop_test
                    else R.string.settings_test_ringtone
                )
            )
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        // ======================================= BACKUP & RESTORE
        Text(
            stringResource(R.string.backup_section),
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(Modifier.height(10.dp))

        val lastBackupText = settings.lastBackupAt?.let { ts ->
            SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
                .format(Date(ts))
        } ?: stringResource(R.string.backup_never)

        Column(Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.backup_drive_account),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = settings.driveAccountEmail?.let {
                    stringResource(R.string.backup_connected_as, it)
                } ?: stringResource(R.string.backup_not_connected),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.backup_last, lastBackupText),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { runDriveOp(PendingDriveOp.Upload) },
                enabled = !driveBusy,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    if (driveBusy) stringResource(R.string.backup_busy)
                    else stringResource(R.string.backup_now)
                )
            }
            OutlinedButton(
                onClick = { runDriveOp(PendingDriveOp.Download) },
                enabled = !driveBusy,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.backup_restore_drive))
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    exportLauncher.launch(
                        context.getString(R.string.backup_file_name)
                    )
                },
                enabled = !driveBusy,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.backup_export_file))
            }
            OutlinedButton(
                onClick = {
                    importLauncher.launch(
                        arrayOf("application/json", "application/octet-stream", "*/*")
                    )
                },
                enabled = !driveBusy,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.backup_import_file))
            }
        }

        Spacer(Modifier.height(6.dp))

        SwitchRow(
            label = stringResource(R.string.backup_auto),
            checked = settings.autoBackupEnabled
        ) { new ->
            onSettingsChange(settings.copy(autoBackupEnabled = new))
        }

        driveStatus?.let { status ->
            Spacer(Modifier.height(6.dp))
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        // ================================================ GOOGLE CALENDAR
        Text(
            stringResource(R.string.calendar_sync_title),
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(Modifier.height(8.dp))

        SwitchRow(
            label = stringResource(R.string.calendar_sync_label),
            checked = settings.calendarSyncEnabled
        ) { new ->
            if (new) {
                if (!calendarConsentPending) enableCalendar()
            } else {
                calendarStatus = null
                calendarConsentPending = false
                onSettingsChange(settings.copy(calendarSyncEnabled = false))
            }
        }

        Spacer(Modifier.height(6.dp))

        Text(
            stringResource(R.string.calendar_sync_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        calendarStatus?.let { status ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        // ================================================ FINISHED TASKS
        // Same pattern as Tutorial / What's new: a button → its own page.
        Text(
            stringResource(R.string.finished_title),
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = onOpenFinished,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.finished_open))
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        // ================================================ TUTORIAL
        Text(
            stringResource(R.string.settings_tutorial),
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = onReplayTutorial,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.settings_replay_tutorial))
        }

        Spacer(Modifier.height(16.dp))

        // ================================================ WHAT'S NEW
        Text(
            stringResource(R.string.whats_new),
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = { showWhatsNew = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.whats_new_open))
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Spacer(Modifier.height(24.dp))

        AboutSection()
    }

    // 📦 Restore result dialog
    restoreResult?.let { result ->
        AlertDialog(
            onDismissRequest = { restoreResult = null },
            title = { Text(stringResource(R.string.backup_restore_title)) },
            text = {
                Text(
                    when (result) {
                        is BackupManager.RestoreResult.NewerVersion ->
                            stringResource(R.string.backup_err_newer)
                        BackupManager.RestoreResult.Corrupt ->
                            stringResource(R.string.backup_err_corrupt)
                        is BackupManager.RestoreResult.Done -> {
                            val s = result.summary
                            if (s.totalAdded == 0) {
                                stringResource(R.string.backup_restore_none)
                            } else {
                                stringResource(
                                    R.string.backup_restore_summary,
                                    s.addedActive,
                                    s.addedCompleted,
                                    s.addedSessions,
                                    if (s.settingsApplied) {
                                        stringResource(
                                            R.string.backup_restore_settings_suffix
                                        )
                                    } else ""
                                )
                            }
                        }
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = { restoreResult = null }) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }

    // 🆕 What's new — every feature added after the v2 release
    if (showWhatsNew) {
        AlertDialog(
            onDismissRequest = { showWhatsNew = false },
            title = { Text(stringResource(R.string.whats_new_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        stringResource(R.string.whats_new_intro),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(12.dp))

                    WhatsNewGroup(R.string.whats_new_release_title, R.array.whats_new_release)
                    WhatsNewGroup(R.string.whats_new_tasks_title, R.array.whats_new_tasks)
                    WhatsNewGroup(R.string.whats_new_focus_title, R.array.whats_new_focus)
                    WhatsNewGroup(R.string.whats_new_progress_title, R.array.whats_new_progress)
                    WhatsNewGroup(R.string.whats_new_backup_title, R.array.whats_new_backup)
                    WhatsNewGroup(R.string.whats_new_personal_title, R.array.whats_new_personal)
                    WhatsNewGroup(R.string.whats_new_fixes_title, R.array.whats_new_fixes)
                }
            },
            confirmButton = {
                TextButton(onClick = { showWhatsNew = false }) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }
}

// ------------------------------------------------------------- composables

/** One-tap −/+ value row used by the numeric preference editors. */
@Composable
private fun StepperRow(
    label: String,
    value: Int,
    range: IntRange,
    step: Int,
    unitRes: Int,
    onValueChange: (Int) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Text(label, Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { onValueChange((value - step).coerceIn(range)) },
                enabled = value > range.first
            ) {
                Icon(
                    Icons.Default.Remove,
                    contentDescription = stringResource(R.string.settings_decrease)
                )
            }
            Text(
                text = stringResource(unitRes, value),
                modifier = Modifier.widthIn(min = 72.dp),
                style = MaterialTheme.typography.bodyMedium
            )
            IconButton(
                onClick = { onValueChange((value + step).coerceIn(range)) },
                enabled = value < range.last
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(R.string.settings_increase)
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun AboutSection() {

    val context = LocalContext.current

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {

        Text(
            "Nudge v${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(Modifier.height(4.dp))

        Text(
            stringResource(R.string.about_tagline),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(10.dp))

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

            Spacer(Modifier.width(6.dp))

            Text(
                stringResource(R.string.about_made_by),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** One "What's new" group: heading + bullet list (readable, not a wall of git). */
@Composable
private fun WhatsNewGroup(titleRes: Int, itemsRes: Int) {
    Text(
        text = stringResource(titleRes),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary
    )

    Spacer(Modifier.height(6.dp))

    stringArrayResource(itemsRes).forEach { item ->
        Row(modifier = Modifier.padding(bottom = 4.dp)) {
            Text(
                text = "•  ",
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = item,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    Spacer(Modifier.height(14.dp))
}