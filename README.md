# 🚀 Nudge – Minimal & Fast To-Do App

> A lightweight, distraction-free productivity app focused on speed, simplicity, and clean design.

---

## ✨ Overview

**Nudge** is a modern Android to-do app designed for people who want to stay productive without dealing with bloated, slow, or overly complex apps.

Built with a focus on **minimal UI, smooth performance, and real-world usability**, Nudge is already being used by 15+ active users.

---

## 📱 Features

**Tasks**
* 📝 Create, edit and complete tasks with date, time, priority (Low/Medium/High) and a critical flag
* 📅 Date-wise groups — Today / Tomorrow / Upcoming — sorted critical-first, then by priority
* ⏳ Live countdown labels ("45 min left", "Due • 10:30")
* 🗑 Delete with undo snackbar; completed list with restore
* 🔔 Exact reminders (survive process death and reboot), auto-cancelled when a task is completed, edited or deleted

**Focus / Pomodoro**
* ⏱ Segment-accurate focus timer that survives process death (end-timestamp based, reconciled on resume)
* 🔁 Cycles with short/long breaks, pause/resume, ±5/±10 adjustments, fullscreen mode
* 🧘 Focus mode with break carry-over — resuming continues where focus stopped
* 🔊 Ringtone alarm with Stop / Snooze +5 actions (foreground service)
* 📋 Live session log with exact per-segment history

**Progress**
* 📊 Calendar with per-day status (planned / on-estimate / over-estimate)
* 📈 Week-over-week focus chart and current streak
* 🎯 Estimate accuracy tracking (10% tolerance)

**Account & data**
* 🔐 Firebase Authentication — email/password and Google Sign-In
* 👤 Guest mode: fully usable without an account (local-only data)
* ☁️ Real-time Firestore sync when signed in (per-user security rules)
* ☁️ Google Drive backup & restore (hidden app-data folder) with merge-on-restore, plus export/import to a file
* 🔄 Automatic background backup toggle

**Personalization**
* 🎨 Accent color picker applied app-wide
* 🌙 Light/dark theme with legacy-theme migration
* 🔔 Custom timer ringtone picker with test preview
* 🚀 First-launch onboarding

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
* 📱 Home-screen widget
* 🌍 Localization (currently English only)
* 📱 Tablet layout

---

## 🤝 Feedback

If you have suggestions or feedback, feel free to reach out!

---

## ⭐ If you like this project

Give it a star ⭐ and share your thoughts!
