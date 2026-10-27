package com.dev.nudge.data.calendar

import android.content.Context
import android.util.Log
import com.dev.nudge.model.Task
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 🔁 Two-way Google Calendar sync.
 *
 * Direction 1 (push): every ACTIVE task with a date + due time becomes an
 * event in the user's primary calendar; completing/deleting/untiming it
 * removes the event. Reconciled via a local taskId→eventId map + payload
 * hash, so unchanged tasks cost ZERO HTTP calls.
 *
 * Direction 2 (pull): [loadDay] reads one day's events from EVERY calendar
 * the account can see — grouped per calendar for the Tasks tab.
 *
 * ⚡ Performance model (was: full authorize + calendarList + events on
 * every navigation and date change, sequential → very slow):
 *  - OAuth token cached in-session (GMS authorize is the slowest step);
 *  - calendar list cached for hours (it changes rarely);
 *  - day results cached with **stale-while-revalidate**: a cached day is
 *    painted instantly, a background refresh runs and bumps [revision],
 *    which makes the Tasks tab re-read the fresh cache;
 *  - events of all calendars fetched in parallel.
 *
 * All HTTP runs on Dispatchers.IO. Consent is only ever requested from the
 * Settings toggle; background syncs never pop dialogs.
 */
object CalendarSync {

    private const val TAG = "NudgeCalendar"
    private const val PREFS = "calendar_sync"
    private const val MAP_KEY = "task_event_map"
    private const val DEFAULT_EVENT_MINUTES = 30

    /** Cached days younger than this are served without any network. */
    private const val DAY_FRESH_MS = 5 * 60_000L
    /** 🩹 Delay before the single auto-retry of a failed cold fetch. */
    private const val RETRY_DELAY_MS = 4_000L
    /** Google access tokens live ~60 min; reuse ours for 50. */
    private const val TOKEN_TTL_MS = 50 * 60_000L
    /** The calendar list changes rarely — refresh it twice a day at most. */
    private const val CALENDARS_TTL_MS = 6 * 60 * 60_000L

    enum class Status { OK, NEEDS_CONSENT, OFFLINE, FAILED }

    /** [changed] = the reconcile actually created/updated/deleted events. */
    data class ReconcileResult(val status: Status, val changed: Boolean)

    /** One calendar's events for the displayed day. */
    data class DayCalendar(
        val calendarTitle: String,
        val events: List<CalendarClient.CalEvent>
    )

    // ------------------------------------------------------------- caches

    private data class DayEntry(var loadedAt: Long, val data: List<DayCalendar>)

    private val cacheLock = Any()
    private val dayCache = LinkedHashMap<String, DayEntry>(8, 0.75f, true)

    @Volatile private var token: String? = null
    @Volatile private var tokenAt = 0L
    @Volatile private var calendars: List<CalendarClient.CalCalendar>? = null
    @Volatile private var calendarsAt = 0L

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 📢 Bumped whenever cached day data changes (background refresh landed,
     * or a reconcile changed events) — the Tasks tab observes this and
     * re-reads [loadDay], which then hits the fresh cache instantly.
     */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val pendingRerun = AtomicBoolean(false)
    /** 🩹 one auto-retry per failure streak — cleared on the next success. */
    private val retryPending = AtomicBoolean(false)

    // ---------------------------------------------------------- data model

    /** What SHOULD exist in the calendar for one task. */
    private data class Desired(
        val taskId: String,
        val title: String,
        val start: Long,
        val end: Long
    ) {
        val payloadHash: String get() = "$title|$start|$end"
    }

    private fun desiredFor(tasks: List<Task>): List<Desired> =
        tasks.asSequence()
            .filter { !it.isCompleted && it.date.isNotBlank() && !it.dueTime.isNullOrBlank() }
            .mapNotNull { t ->
                val start = parseWhen(t.date, t.dueTime ?: return@mapNotNull null)
                    ?: return@mapNotNull null
                val minutes = (t.estimatedMinutes ?: DEFAULT_EVENT_MINUTES)
                    .coerceIn(5, 24 * 60)
                Desired(
                    taskId = t.id,
                    title = t.title.ifBlank { "Nudge task" },
                    start = start,
                    end = start + minutes * 60_000L
                )
            }
            .toList()

    /** "2026-10-26" + "14:30" → epoch millis in the device zone. */
    private fun parseWhen(date: String, time: String): Long? = runCatching {
        LocalDateTime.parse("${date.trim()}T${time.trim()}:00")
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrNull()

    // ------------------------------------------------------- local id map

    /** taskId → (eventId, payload hash). Small enough for SharedPreferences. */
    private fun loadMap(context: Context): MutableMap<String, Pair<String, String>> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(MAP_KEY, null) ?: return mutableMapOf()
        return runCatching {
            json.parseToJsonElement(raw).jsonObject.mapValues { (_, el) ->
                val o = el.jsonObject
                val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapValues "" to ""
                val hash = o["hash"]?.jsonPrimitive?.contentOrNull ?: ""
                id to hash
            }.filterValues { it.first.isNotBlank() }.toMutableMap()
        }.getOrDefault(mutableMapOf())
    }

    private fun saveMap(context: Context, map: Map<String, Pair<String, String>>) {
        val raw = buildJsonObject {
            map.forEach { (taskId, v) ->
                putJsonObject(taskId) {
                    put("id", v.first)
                    put("hash", v.second)
                }
            }
        }.toString()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(MAP_KEY, raw).apply()
    }

    // ------------------------------------------------------------- push

    /**
     * Make the primary calendar match the CURRENT active task list.
     * [tasks] is re-read inside the lock so a burst of edits always ends
     * with a pass over the latest state.
     */
    suspend fun reconcile(context: Context, tasks: () -> List<Task>): ReconcileResult =
        withContext(Dispatchers.IO) {
            if (mutex.isLocked) {
                pendingRerun.set(true) // a running pass will pick this up
                return@withContext ReconcileResult(Status.OK, changed = false)
            }
            var result: ReconcileResult
            mutex.withLock {
                do {
                    pendingRerun.set(false)
                    result = once(context, tasks())
                } while (pendingRerun.get())
            }
            result
        }

    private suspend fun once(context: Context, tasks: List<Task>): ReconcileResult {
        // Writes use a FRESH authorize (not the cached read token): a stale
        // cached token would 401-loop; GMS answers silently once granted.
        val auth = CalendarClient.authorize(context)
        val writeToken = when (auth) {
            is CalendarClient.CalAuth.Authorized -> auth.accessToken
            is CalendarClient.CalAuth.Failed -> {
                Log.w(TAG, "sync: auth failed — ${auth.reason}")
                return ReconcileResult(Status.NEEDS_CONSENT, changed = false)
            }
            is CalendarClient.CalAuth.NeedsResolution -> {
                Log.d(TAG, "sync skipped — consent required")
                return ReconcileResult(Status.NEEDS_CONSENT, changed = false)
            }
        }
        // ⚡ the same token is valid for READS too — hand it to the read path
        //    so the next loadDay() skips its own GMS authorize. That call used
        //    to run CONCURRENTLY with this reconcile right after an add, which
        //    is what made the calendar section come back empty.
        token = writeToken
        tokenAt = System.currentTimeMillis()

        val map = loadMap(context)
        val desired = desiredFor(tasks)
        val byTask = desired.associateBy { it.taskId }
        var changed = false

        try {
            // 1) upsert every timed task that is new or changed
            for (d in desired) {
                val existing = map[d.taskId]
                if (existing != null && existing.second == d.payloadHash) continue

                if (existing != null) {
                    try {
                        CalendarClient.updateEvent(
                            writeToken, existing.first, d.title, d.start, d.end
                        )
                        map[d.taskId] = existing.first to d.payloadHash
                        changed = true
                        continue
                    } catch (e: CalendarClient.CalException) {
                        if (e.code != 404 && e.code != 410) throw e
                        // event vanished in Google's UI → recreate below
                    }
                }
                val id = CalendarClient.insertEvent(writeToken, d.title, d.start, d.end)
                map[d.taskId] = id to d.payloadHash
                changed = true
                Log.w(TAG, "created event for task ${d.taskId}")
            }

            // 2) remove events whose task is gone / completed / lost its time
            val stale = map.keys - byTask.keys
            for (taskId in stale) {
                val id = map[taskId]?.first ?: continue
                CalendarClient.deleteEvent(writeToken, id)
                map.remove(taskId)
                changed = true
                Log.w(TAG, "removed event for task $taskId")
            }

            saveMap(context, map)
            return ReconcileResult(Status.OK, changed)
        } catch (e: Exception) {
            saveMap(context, map) // keep whatever succeeded
            Log.w(TAG, "sync failed: ${e::class.java.name}: ${e.message}")
            val status = if (e is java.io.IOException) Status.OFFLINE else Status.FAILED
            return ReconcileResult(status, changed)
        }
    }

    // -------------------------------------------------------- read path

    /**
     * 📅 Events of one local day from EVERY calendar the account can see.
     *
     * Instant on cache hit; on a stale hit the cached data is returned at
     * once and a background refresh bumps [revision]. Unreadable calendars
     * are skipped one by one. Empty on no-consent / offline / errors.
     */
    suspend fun loadDay(context: Context, iso: String): List<DayCalendar> {
        val t0 = System.currentTimeMillis()
        val hit = synchronized(cacheLock) { dayCache[iso] }
        if (hit != null && System.currentTimeMillis() - hit.loadedAt < DAY_FRESH_MS) {
            // ⚡ pure cache — no GMS, no HTTP
            Log.w(TAG, "day $iso: cache hit in ${System.currentTimeMillis() - t0}ms")
            return hit.data
        }

        if (hit != null) {
            // 🔄 stale-while-revalidate: paint now, refresh behind the scenes
            Log.w(TAG, "day $iso: stale cache served in ${System.currentTimeMillis() - t0}ms — refreshing")
            ioScope.launch {
                val t1 = System.currentTimeMillis()
                fetch(context, iso)?.let { fresh ->
                    synchronized(cacheLock) {
                        dayCache[iso] = DayEntry(System.currentTimeMillis(), fresh)
                    }
                    retryPending.set(false)
                    Log.w(TAG, "day $iso: background refresh done in ${System.currentTimeMillis() - t1}ms")
                    _revision.value = _revision.value + 1
                }
            }
            return hit.data
        }

        val fetched = fetch(context, iso)
        Log.w(TAG, "day $iso: cold fetch ${if (fetched == null) "FAILED" else "ok"} in ${System.currentTimeMillis() - t0}ms")
        if (fetched == null) {
            // 🩹 a transient failure must not leave the section blank until
            //    the user happens to change date — try once more shortly.
            scheduleRetry()
        }
        return fetched?.also { data ->
            retryPending.set(false)
            synchronized(cacheLock) {
                dayCache[iso] = DayEntry(System.currentTimeMillis(), data)
            }
        } ?: emptyList()
    }

    /**
     * 🧹 Mark every cached day STALE (after a reconcile changed events) and
     * make viewers re-read.
     *
     * ⚠️ The data is deliberately NOT dropped: dropping it forced a cold
     * network fetch on the very next frame, so the calendar section blanked
     * out every time a task was added (and stayed blank if that fetch
     * failed). Stale-while-revalidate keeps the old events on screen while
     * a background refresh swaps in the fresh ones.
     */
    fun invalidateDays() {
        synchronized(cacheLock) {
            dayCache.values.forEach { entry -> entry.loadedAt = 0L }
        }
        _revision.value = _revision.value + 1
    }

    /** Schedule ONE delayed revision bump after a failed cold fetch. */
    private fun scheduleRetry() {
        if (!retryPending.compareAndSet(false, true)) return
        ioScope.launch {
            delay(RETRY_DELAY_MS)
            _revision.value = _revision.value + 1
        }
    }

    /** Session-cached OAuth token — GMS authorize is the slowest step. */
    private suspend fun validToken(context: Context): String? {
        token?.let { t ->
            if (System.currentTimeMillis() - tokenAt < TOKEN_TTL_MS) return t
        }
        return when (val a = CalendarClient.authorize(context)) {
            is CalendarClient.CalAuth.Authorized -> a.accessToken.also {
                token = it
                tokenAt = System.currentTimeMillis()
            }
            is CalendarClient.CalAuth.Failed -> {
                Log.w(TAG, "auth failed: ${a.reason}")
                null
            }
            is CalendarClient.CalAuth.NeedsResolution -> {
                Log.w(TAG, "consent required")
                null
            }
        }
    }

    private suspend fun fetch(context: Context, iso: String): List<DayCalendar>? =
        withContext(Dispatchers.IO) {
            val first = validToken(context) ?: return@withContext null
            try {
                doFetch(first, iso)
            } catch (e: CalendarClient.CalException) {
                if (e.code == 401) {
                    Log.w(TAG, "token rejected — reauthorizing once")
                    token = null
                    val second = validToken(context) ?: return@withContext null
                    runCatching { doFetch(second, iso) }.getOrElse { err ->
                        Log.w(TAG, "fetch failed: ${err.message}")
                        null
                    }
                } else {
                    Log.w(TAG, "fetch failed: HTTP ${e.code}")
                    null
                }
            } catch (e: Exception) {
                Log.w(TAG, "fetch failed: ${e::class.java.name}: ${e.message}")
                null
            }
        }

    private suspend fun doFetch(token: String, iso: String): List<DayCalendar> {
        val zone = ZoneId.systemDefault()
        val day = LocalDate.parse(iso)
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val cals = calendarList(token)

        // 🚀 all calendars in parallel — total latency = slowest single call
        return coroutineScope {
            cals.map { cal ->
                async {
                    val events = try {
                        CalendarClient.listEvents(token, cal.id, start, end)
                    } catch (e: Exception) {
                        Log.w(TAG, "list '${cal.id}' failed: ${e.message}")
                        null
                    }
                    if (events.isNullOrEmpty()) null else DayCalendar(cal.title, events)
                }
            }.awaitAll().filterNotNull()
                .sortedBy { group ->
                    group.events.minOfOrNull { it.startMillis } ?: Long.MAX_VALUE
                }
        }
    }

    /** Calendar list with a long-lived cache; falls back to `primary`. */
    private fun calendarList(token: String): List<CalendarClient.CalCalendar> {
        calendars?.let { cached ->
            if (System.currentTimeMillis() - calendarsAt < CALENDARS_TTL_MS) return cached
        }
        val primary = listOf(CalendarClient.CalCalendar("primary", "Primary", false))
        val fetched = runCatching { CalendarClient.listCalendars(token) }.getOrNull()
        val visible = fetched?.filter { !it.hidden }?.ifEmpty { null }
        return if (visible != null) {
            visible.also {
                calendars = it
                calendarsAt = System.currentTimeMillis()
            }
        } else {
            // unreachable just now — a stale list still beats none
            calendars ?: primary
        }
    }
}
