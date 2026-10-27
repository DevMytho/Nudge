# 🚀 Nudge – Minimal & Fast To-Do App

> A lightweight, distraction-free productivity app focused on speed, simplicity, and clean design.
>
> **Current release: v3.01.0**

---

## ✨ Overview

**Nudge** is a modern Android to-do app designed for people who want to stay productive without dealing with bloated, slow, or overly complex apps.

Built with a focus on **minimal UI, smooth performance, and real-world usability**, Nudge is already being used by 15+ active users.

---

## 📱 Features

**Tasks**
* 📝 Create, edit and complete tasks with date, time, priority (Low/Medium/High) and a critical flag
* 📅 Date-wise Tasks tab: one day at a time with a Day / All toggle, week strip, carried-over section and strike-off in place
* ⏳ Live countdown labels ("45 min left", "Due • 10:30")
* 🗑 Delete with undo snackbar
* ✅ **Finished-tasks page** (Settings → Finished tasks): everything you've done, with one tap to **re-schedule it to today** or **delete it permanently**
* 🔔 Exact reminders (survive process death and reboot), auto-cancelled when a task is completed, edited or deleted

**Focus / Pomodoro**
* ⏱ Segment-accurate focus timer that survives process death (end-timestamp based, reconciled on resume)
* 🔁 Cycles with short/long breaks, pause/resume, ±5/±10 adjustments, fullscreen mode
* 🧘 Focus mode with break carry-over — resuming continues where focus stopped
* 🔊 Ringtone alarm with Stop / Snooze +5 actions (foreground service)
* 📋 Live session log with exact per-segment history

**Progress**
* 📊 Month calendar with per-day status rings and a per-day detail sheet
* 🔥 Streak (current + best) that **updates the same day you finish a task** — no waiting for focus minutes
* 📈 Week-over-week focus chart and focused-time charts (7 / 30 days)
* 🎯 Estimate accuracy tracking (10% tolerance)

**Home-screen widgets**
* 🔷 Small widget: current streak + today's progress line
* 📋 Larger widget: a window onto today's tasks (up to 6 rows + overflow), refreshed whenever tasks change — no stale or repeated rows

**Google Calendar (two-way)**
* 📅 Timed tasks become events in your primary calendar — created, updated and deleted as you edit, complete or remove them (reconciled, so unchanged tasks cost no network)
* 🗓 Your day's events shown on the Tasks tab, **from every calendar your account can see** (shared calendars like a college mail included — auto-discovered)
* 🔑 One consent prompt from Settings; background syncs never pop dialogs

**Account & data**
* 🔐 Firebase Authentication — email/password and Google Sign-In
* 👤 Guest mode: fully usable without an account (local-only data)
* ☁️ Real-time Firestore sync when signed in (per-user security rules)
* ☁️ Google Drive backup & restore (hidden app-data folder) with merge-on-restore, plus export/import to a file
* 🔄 Automatic background backup on app exit — runs off the main thread, with the outcome always visible in logcat (`NudgeDrive`)

**Personalization & info**
* 🎨 Accent color picker applied app-wide
* 🌙 Light/dark theme with legacy-theme migration
* 🔔 Custom timer ringtone picker with test preview + optional ring for Critical reminders
* 🚀 First-launch onboarding, replayable any time from Settings
* 🆕 **What's New** section in Settings — every feature since v2 in plain words

---

## 🧠 Why Nudge?

Most productivity apps today are:

* cluttered
* slow
* overloaded with features

**Nudge solves this by focusing on what actually matters:**

> Speed, simplicity, and usability.

---

## 🛠️ Tech Stack

* **Language:** Kotlin
* **UI:** Jetpack Compose (Material 3), single-activity navigation
* **Local data:** DataStore (tasks/settings) + Room (focus sessions)
* **Backend:** Firebase Authentication + Cloud Firestore
* **Cloud backup:** Google Drive API (REST v3, app-data folder)
* **Calendar:** Google Calendar API (REST — `calendar.events` + `calendar.calendarlist` scopes)
* **Widgets:** classic `AppWidgetProvider` / RemoteViews (streak + tasks)
* **Alarms:** `AlarmManager.setAlarmClock` + foreground service for ringing
* **Tools:** Android Studio, Gradle (Kotlin DSL)

---

## 🗂 Architecture notes & QA

* [`docs/DECISIONS.md`](docs/DECISIONS.md) — why the non-obvious choices are the way they are (reconcile semantics, session sync, backup merge rules…)
* [`docs/QA.md`](docs/QA.md) — automated checks, unit-test inventory, manual QA checklist

Run everything:

```bash
./gradlew test lint assembleDebug assembleRelease
```

---

## 👨‍💻 My Role

* Designed and developed the complete app independently
* Focused on performance optimization and smooth UX
* Implemented authentication and real-time data handling
* Built with a product-first mindset, not just as a project

---

## 🚀 Future Improvements

* 🔍 Search and filters across tasks
* ♻️ Recurring tasks
* 📱 Widget interaction (tap individual task rows to complete)
* 🌍 Localization (currently English only)
* 📱 Tablet layout

---

## 🤝 Feedback

If you have suggestions or feedback, feel free to reach out!

---

## ⭐ If you like this project

Give it a star ⭐ and share your thoughts!
