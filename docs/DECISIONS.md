# Decision log

Short records of the non-obvious choices made while building Nudge, so the
code comments that say "see docs/DECISIONS.md" have something to point at.
Each entry: the decision, and why.

---

## 1. Reconcile never auto-starts a phase

**Decision:** when `TimerEngine.reconcile()` catches up after process death,
reboot, or a missed alarm, it closes the overdue phase stamped with its
*planned* end time and advances to the next phase **paused** — it never
auto-starts the next phase.

**Why:** the user was not present when the phase ended. Closing at the planned
end keeps session logs and statistics exact (no phantom focus time), while
leaving the next phase paused means returning hours later never silently drops
you into a running timer that accrues fake minutes. Resuming is always an
explicit user action. The normal in-process path (`autoStart = true`) still
chains phases back-to-back when the app is alive.

---

## 2. Pomodoro start-screen overrides are in-memory only

**Decision:** session-only length overrides chosen on the Pomodoro start
screen (`TimerViewModel._sessionOverrides`) live in memory for the life of the
process. After process death the next phase falls back to the saved settings
from `SettingsStorage`.

**Why:** persisting a one-off override would silently rewrite the user's
saved defaults — "run this session with 15-minute focus" would become "change
my settings to 15 minutes forever". When persistence and user intent conflict,
saved settings win. The trade-off (overrides vanish on process death) is
noted in the start-screen UI copy.

---

## 3. Room is the source of truth for sessions; Firestore gets a one-way push

**Decision:** focus sessions and segments are read from and written to Room
for **all** users (guest and signed-in). Signed-in users additionally get a
one-way push to `users/{uid}/sessions/{sessionId}` with the segments embedded
as an array field on the session document. No snapshot listeners read session
data back from Firestore.

**Why:** session writes are append-heavy (a segment closes every few minutes,
segments update on pause/resume). One document per session with an embedded
segment array keeps the document count fixed and turns each segment update
into a single overwrite — cheap, idempotent, and trivially retryable. Pulling
the data back down would require conflict-merge logic for a collection nobody
edits from a second device live; skipping the read path removes that whole
class of bugs. Tasks (shared, read-heavy, edited interactively) keep their
existing two-way listener sync — the asymmetry is deliberate.

---

## 4. The ongoing session notification stays minimal (no action buttons)

**Decision:** the persistent notification shown during a running focus
session displays only the phase label and remaining time; tapping opens the
app. Pause/±5-minute action buttons are not implemented.

**Why:** every timer transition must go through `TimerEngine` in one place.
Notification actions would need a second entry path (broadcast → view model →
engine) with its own pending-intent request codes and state reconciliation —
double-handling risk for little gain, since the app is already open or one
tap away. The notification is informational; controls live in the Focus tab.

---

## 5. A day with focus but no estimate counts as "on estimate"

**Decision:** in the Progress calendar, a day that has focus time but no
estimate attached to any focused task renders as **ON_ESTIMATE**, not OVER and
not a separate state (`StatsCalculator.dayCellState`).

**Why:** accuracy classification asks "did you stay within plan?" — with no
plan there is nothing to exceed, so scoring it as over would punish the user
for working without estimates. Keeping the four states (empty / planned /
on / over) keeps the legend and cell colors simple. Days with focus and an
estimate use the strict integer test `actual × 10 ≤ estimate × 11` (a 10 %
tolerance without float rounding).

---

## 6. Backup restore is a merge, never a wipe

**Decision:** restoring a backup performs a three-way apply: tasks merge as a
union by id (local wins on conflicts, backup adds what's missing), sessions
insert only ids this device doesn't already have, and settings are overlaid
onto this device's settings (backup/account fields like the Drive grant and
sync flags are kept local). A backup from a *newer* schema version or a
corrupt file is rejected untouched.

**Why:** restore is used on a new or shared device where local data may be
precious; a destructive overwrite turns a mistake into data loss. Union-merge
is idempotent — running the same restore twice adds nothing the second time —
and "local wins" keeps this device's state authoritative for anything that
already exists here.

---

## 7. Drive backup uses the hidden app-data folder with explicit consent

**Decision:** backups live in the Drive **appdata** folder (`drive.appdata`
scope), invisible in the user's My Drive listing, written through the REST v3
API with a single pinned filename plus an `exportedAt`/version header. All
Drive calls require a fresh authorization grant; auto-backup silently skips
if consent is needed rather than popping a dialog from the background.

**Why:** the app should never show, list, or ask the user to manage backup
files — one canonical slot with last-write-wins on upload. A restricted
folder-scoped OAuth grant beats `drive.file` on user trust: the app sees only
what it created itself. Background auto-backup running on app-to-background
must never surface consent UI, so a missing grant is treated as "skip this
round", surfaced once in Settings instead.
