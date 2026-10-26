# QA notes

How the current state of Nudge is verified, and what's still manual.

## Automated checks (run from the repo root)

```bash
./gradlew test lint assembleDebug assembleRelease
```

| Check | What it covers |
|---|---|
| `test` | JVM unit tests (see below) |
| `lint` | Android lint on all variants |
| `assembleDebug` | debug APK builds clean |
| `assembleRelease` | release APK builds clean (requires `local.properties` signing keys, optional) |

Last full run: **91 tests, 0 failures, lint clean, both APKs build.**

> 📱 Note: some OEM ROMs drop `Log.d` from release builds. Every
> decision-relevant log in Nudge is therefore at `W` — watch `NudgeDrive`
> (backup) and `NudgeCalendar` (sync) with `adb logcat -s NudgeDrive:V NudgeCalendar:V`.

## Unit test inventory

| File | Tests | Covers |
|---|---|---|
| `TaskUtilsTest` | 27 | Display countdown labels, Today/Tomorrow/Upcoming grouping, critical-then-priority sort, date normalization (pre-v3 → v3 migration) |
| `TimerEngineTest` | 25 | Phase transitions, pause/resume, ±adjustments, cycle advance, `reconcile()` after process death (never auto-starts), focus-mode break carry-over |
| `StreakCalculatorTest` | 12 | Streak counting incl. gap/zero-day edges, timezone/DST-safe day keys, **completion days** (task finished today counts today, legacy tasks without a timestamp fall back to their planned day) |
| `StatsCalculatorTest` | 8 | Estimate accuracy (10 % integer tolerance), day-cell states (incl. focus-without-estimate → ON), day totals from segments |
| `BackupCodecTest` | 7 | JSON round-trip, corrupt input rejection, newer-schema-version rejection, defaults for missing fields |
| `BackupMergerTest` | 6 | Union merge by id, local-wins conflicts, completed↔active collisions, session-id dedupe, added-count reporting |
| `ReminderTriggerTest` | 4 | Reminder scheduling/cancellation rules (complete/edit/delete/restore) |
| `FirestoreTaskMappingTest` | 1 | Firestore doc → Task mapping regression (the pre-v3 "conflicting getters" crash) |
| `ExampleUnitTest` | 1 | template placeholder |

All logic that touches time is deterministic: tests inject fixed clocks /
timestamps rather than reading the wall clock.

## Manual QA checklist (device/emulator)

**Tasks**
- [ ] Create a task with date/time, priority, critical flag → appears in the right group (Today/Tomorrow/Upcoming) with correct countdown label
- [ ] Edit a task → reminder reschedules (same alarm id)
- [ ] Complete a task → reminder is cancelled, task lands in Completed
- [ ] Restore / delete in Completed; delete active task shows undo snackbar and undo restores it **with** its reminder rescheduled
- [ ] Kill the app, reboot → reminders still fire (BootReceiver)
- [ ] Settings → **Finished tasks** opens the page; **Re-schedule to today** returns the task to today active; **Delete** removes it locally and from Firestore (signed in)

**Widgets**
- [ ] Place both widgets (streak + tasks) → values match the app
- [ ] Complete/add a task → both widgets refresh without reopening the app
- [ ] Open the app repeatedly → the tasks widget list never duplicates rows
- [ ] Reboot → widgets refresh (BootReceiver)

**Auth & sync**
- [ ] Register / login with email, login with Google, guest mode without any login
- [ ] Two devices signed in → task edits appear live on both
- [ ] Logout wipes local guest data (tasks, sessions) and clears Drive grant state

**Focus / Pomodoro**
- [ ] Start a session, background the app, kill it, reopen → remaining time correct (end-timestamp reconcile, next phase paused)
- [ ] Pause/resume, ±5/±10 adjustments, phase rollover rings the chosen ringtone (Stop and Snooze +5 actions work, auto-stop after 60 s)
- [ ] Session log shows exact segments on the Focus tab; ongoing notification tracks phase + remaining time

**Progress tab**
- [ ] Calendar day cells match the legend (empty / planned / on / over)
- [ ] Week-over-week chart and streak update after a session is recorded
- [ ] Finish a task → **streak grows the same day** (no focus minutes needed), incl. tasks completed before the v3 update (no completion timestamp)
- [ ] "Focus with no estimate" day renders as on-estimate (decision #5)

**Backup & restore**
- [ ] Drive sign-in grant → manual backup succeeds; auto-backup on background works when enabled — verify via `adb logcat -s NudgeDrive:V` (`auto-backup OK (email=…)`)
- [ ] Restore on a fresh install adds tasks + sessions + settings, shows the summary counts; restoring the same file twice adds 0
- [ ] Corrupt file and newer-version file are rejected with a message, local data untouched
- [ ] Export to file / import from file round-trips identically (SAF picker)
- [ ] Auto-backup with expired grant skips silently (no dialog from background)

**Google Calendar** (prerequisites: Calendar API enabled in the Google Cloud project, and the desired calendars shared with the signed-in account)
- [ ] Settings → Google Calendar → toggle on → consent screen appears once; sync stays silent afterwards
- [ ] Add/edit/complete/delete a task **with a time** → matching event appears/updates/disappears in the primary calendar
- [ ] Tasks tab shows the selected day's events from **all** the account's calendars, labelled per calendar when there is more than one
- [ ] Speed: first visit to a date logs `cold fetch ok …ms`; revisiting / returning from Pomodoro logs `cache hit in 0ms`; after 5 min the strip paints instantly while `background refresh` runs
- [ ] Calendar API disabled → section hides, no crash, one `HTTP 403` warning in `NudgeCalendar`

**Personalization / onboarding / settings**
- [ ] First launch shows onboarding; accent color change applies app-wide immediately
- [ ] Legacy theme prefs migrate on upgrade; dark mode toggle persists across restart
- [ ] Changing settings (ringtone, focus defaults, goals) takes effect without app restart
- [ ] Settings order at the bottom reads as one flow: Backup → Google Calendar → Finished tasks → Tutorial → What's new → About
- [ ] **What's new** dialog lists every post-v2 feature in the newest-release group first

## Known limitations
- Localization: English strings only, hardcoded in Kotlin UI code.
- No tablet-specific layout.
- Google Calendar: task events are written to the **primary** calendar only; secondary calendars are read-only (they must also be *shared with* the signed-in account — no app can read another account's calendar otherwise). The Calendar API must be enabled in the Google Cloud project.
- Some OEM ROMs drop `Log.d` from release builds — diagnostics are logged at `W` under `NudgeDrive` / `NudgeCalendar`.
- Release APK requires signing keys in `local.properties` (see `local.properties.example`); unsigned release builds are expected to fail at signing.
