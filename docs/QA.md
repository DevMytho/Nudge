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

Last full run: **82 tests, 0 failures, lint clean, both APKs build.**

## Unit test inventory

| File | Tests | Covers |
|---|---|---|
| `TaskUtilsTest` | 27 | Display countdown labels, Today/Tomorrow/Upcoming grouping, critical-then-priority sort, date normalization (pre-v3 → v3 migration) |
| `TimerEngineTest` | 25 | Phase transitions, pause/resume, ±adjustments, cycle advance, `reconcile()` after process death (never auto-starts), focus-mode break carry-over |
| `BackupCodecTest` | 7 | JSON round-trip, corrupt input rejection, newer-schema-version rejection, defaults for missing fields |
| `BackupMergerTest` | 6 | Union merge by id, local-wins conflicts, completed↔active collisions, session-id dedupe, added-count reporting |
| `StreakCalculatorTest` | 8 | Streak counting incl. gap/zero-day edges and timezone-safe day keys |
| `StatsCalculatorTest` | 8 | Estimate accuracy (10 % integer tolerance), day-cell states (incl. focus-without-estimate → ON), day totals from segments |
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
- [ ] "Focus with no estimate" day renders as on-estimate (decision #5)

**Backup & restore**
- [ ] Drive sign-in grant → manual backup succeeds; auto-backup on background works when enabled
- [ ] Restore on a fresh install adds tasks + sessions + settings, shows the summary counts; restoring the same file twice adds 0
- [ ] Corrupt file and newer-version file are rejected with a message, local data untouched
- [ ] Export to file / import from file round-trips identically (SAF picker)
- [ ] Auto-backup with expired grant skips silently (no dialog from background)

**Personalization / onboarding**
- [ ] First launch shows onboarding; accent color change applies app-wide immediately
- [ ] Legacy theme prefs migrate on upgrade; dark mode toggle persists across restart
- [ ] Changing settings (ringtone, focus defaults, goals) takes effect without app restart

## Known limitations
- Localization: English strings only, hardcoded in Kotlin UI code.
- No tablet-specific layout; no widgets.
- Release APK requires signing keys in `local.properties` (see `local.properties.example`); unsigned release builds are expected to fail at signing.
