package com.dev.nudge.data.calendar

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 📅 Google Calendar transport (two-way sync, `calendars/primary`).
 *
 * One scope — `calendar.events` — covers BOTH directions: creating/updating/
 * deleting our task events AND reading the user's own events for display.
 * Auth reuses the same modern [Identity] AuthorizationClient pattern as
 * Drive backup (no deprecated GoogleSignInClient).
 *
 * All functions here are blocking → callers wrap them in Dispatchers.IO
 * (never call from the main thread — see DriveBackupClient history).
 */
object CalendarClient {

    private const val TAG = "NudgeCalendar"

    /** View+edit events on ALL calendars the account can access. */
    const val SCOPE_EVENTS = "https://www.googleapis.com/auth/calendar.events"
    /** Narrow scope: list the calendars the account is subscribed to. */
    const val SCOPE_CALENDAR_LIST =
        "https://www.googleapis.com/auth/calendar.calendarlist.readonly"

    private const val CAL_URL = "https://www.googleapis.com/calendar/v3/calendars"
    private const val PRIMARY_EVENTS_URL = "$CAL_URL/primary/events"
    private const val CAL_LIST_URL =
        "https://www.googleapis.com/calendar/v3/users/me/calendarList"

    private val json = Json { ignoreUnknownKeys = true }
    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** One calendar entry, normalized for display. */
    data class CalEvent(
        val id: String,
        val title: String,
        val startMillis: Long,
        val endMillis: Long,
        val allDay: Boolean
    )

    /** One calendar the account can see (primary, shared, or subscribed). */
    data class CalCalendar(
        val id: String,
        val title: String,
        val hidden: Boolean
    )

    sealed interface CalAuth {
        data class Authorized(val accessToken: String) : CalAuth
        data class NeedsResolution(val pendingIntent: PendingIntent) : CalAuth
        data class Failed(val reason: String) : CalAuth
    }

    /** Thrown for any non-success HTTP response; [code] is the status. */
    class CalException(val code: Int, body: String) :
        Exception("HTTP $code: ${body.take(200)}")

    // ------------------------------------------------------------- auth

    private fun buildRequest(): AuthorizationRequest =
        AuthorizationRequest.Builder()
            .setRequestedScopes(listOf(Scope(SCOPE_EVENTS), Scope(SCOPE_CALENDAR_LIST)))
            .build()

    /** Silent when the calendar scope was already granted. */
    suspend fun authorize(context: Context): CalAuth = try {
        val result = Identity.getAuthorizationClient(context)
            .authorize(buildRequest())
            .await()
        val token = result.accessToken
        val pending = result.pendingIntent
        when {
            result.hasResolution() && pending != null -> CalAuth.NeedsResolution(pending)
            result.hasResolution() -> CalAuth.Failed("missing resolution intent")
            !token.isNullOrBlank() -> CalAuth.Authorized(token)
            else -> CalAuth.Failed("no access token")
        }.also {
            Log.d(TAG, "authorize -> " +
                (if (it is CalAuth.NeedsResolution) "consent required" else it::class.simpleName))
        }
    } catch (e: Exception) {
        Log.w(TAG, "authorize threw: ${e.message}")
        CalAuth.Failed(e.message ?: "authorization failed")
    }

    /** Extract the consent result from the resolution Intent the UI launched. */
    fun authFromIntent(context: Context, data: Intent?): CalAuth {
        if (data == null) return CalAuth.Failed("authorization cancelled")
        return try {
            val r = Identity.getAuthorizationClient(context)
                .getAuthorizationResultFromIntent(data)
            val pending = r.pendingIntent
            val token = r.accessToken
            when {
                r.hasResolution() && pending != null -> CalAuth.NeedsResolution(pending)
                !token.isNullOrBlank() -> CalAuth.Authorized(token)
                else -> CalAuth.Failed("no access token")
            }
        } catch (e: Exception) {
            CalAuth.Failed(e.message ?: "authorization failed")
        }
    }

    // ------------------------------------------------------------ events

    /**
     * 📚 Every calendar the signed-in account is subscribed to (primary +
     * shared/secondary ones — e.g. a college calendar shared to a personal
     * account). Deleted entries are dropped; [CalCalendar.hidden] is left to
     * the caller. Falls back to an empty list on errors (caller defaults to
     * `primary`).
     */
    fun listCalendars(token: String): List<CalCalendar> {
        val (code, body) = request("$CAL_LIST_URL?maxResults=250", "GET", token)
        if (code !in 200..299) throw CalException(code, body)
        return runCatching {
            json.parseToJsonElement(body).jsonObject["items"]?.jsonArray
                .orEmpty().mapNotNull { el ->
                    val o = el.jsonObject
                    val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    if (o["deleted"]?.jsonPrimitive?.contentOrNull == "true") {
                        return@mapNotNull null
                    }
                    val title = o["summaryOverride"]?.jsonPrimitive?.contentOrNull
                        ?: o["summary"]?.jsonPrimitive?.contentOrNull
                        ?: id
                    CalCalendar(
                        id = id,
                        title = title,
                        hidden = o["hidden"]?.jsonPrimitive?.contentOrNull == "true"
                    )
                }
        }.getOrElse { e ->
            Log.w(TAG, "calendarList failed: ${e.message}")
            emptyList()
        }
    }

    /** Events of ONE calendar overlapping [startMillis, endMillis). */
    fun listEvents(
        token: String,
        calendarId: String,
        startMillis: Long,
        endMillis: Long
    ): List<CalEvent> {
        val fmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        fun iso(ms: Long) = fmt.format(Instant.ofEpochMilli(ms).atZone(zone))
        val query = buildString {
            append("?timeMin=").append(URLEncoder.encode(iso(startMillis), "UTF-8"))
            append("&timeMax=").append(URLEncoder.encode(iso(endMillis), "UTF-8"))
            append("&singleEvents=true&orderBy=startTime&maxResults=100")
        }
        val url = "$CAL_URL/${URLEncoder.encode(calendarId, "UTF-8")}/events$query"
        val (code, body) = request(url, "GET", token)
        if (code !in 200..299) throw CalException(code, body)

        return runCatching {
            json.parseToJsonElement(body).jsonObject["items"]?.jsonArray.orEmpty().mapNotNull { el ->
                val o = el.jsonObject
                if (o["status"]?.jsonPrimitive?.contentOrNull == "cancelled") return@mapNotNull null
                val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val startEl = o["start"]?.jsonObject ?: return@mapNotNull null
                val endEl = o["end"]?.jsonObject ?: return@mapNotNull null
                val startDate = startEl["date"]?.jsonPrimitive?.contentOrNull
                val startDt = startEl["dateTime"]?.jsonPrimitive?.contentOrNull
                val endDt = endEl["dateTime"]?.jsonPrimitive?.contentOrNull
                val endDate = endEl["date"]?.jsonPrimitive?.contentOrNull
                when {
                    startDate != null -> {
                        val s = runCatching {
                            LocalDate.parse(startDate).atStartOfDay(zone).toInstant().toEpochMilli()
                        }.getOrNull() ?: return@mapNotNull null
                        val e = runCatching {
                            (LocalDate.parse(endDate ?: startDate)).atStartOfDay(zone)
                                .toInstant().toEpochMilli()
                        }.getOrDefault(s + 86_400_000L)
                        CalEvent(id, o["summary"]?.jsonPrimitive?.contentOrNull ?: "", s, e, true)
                    }
                    startDt != null -> {
                        val s = runCatching { OffsetDateTime.parse(startDt).toInstant().toEpochMilli() }
                            .getOrNull() ?: return@mapNotNull null
                        val e = endDt?.let {
                            runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }
                                .getOrNull()
                        } ?: (s + 30 * 60_000L)
                        CalEvent(id, o["summary"]?.jsonPrimitive?.contentOrNull ?: "", s, e, false)
                    }
                    else -> null
                }
            }
        }.getOrElse { e ->
            Log.w(TAG, "list parse failed: ${e.message}")
            emptyList()
        }
    }

    /** Create an event in the PRIMARY calendar; returns its Google event id. */
    fun insertEvent(token: String, title: String, startMillis: Long, endMillis: Long): String {
        val (code, body) = request(
            PRIMARY_EVENTS_URL, "POST", token, eventJson(title, startMillis, endMillis)
        )
        if (code !in 200..299) throw CalException(code, body)
        return runCatching {
            json.parseToJsonElement(body).jsonObject["id"]?.jsonPrimitive?.contentOrNull
        }.getOrNull() ?: throw CalException(code, "missing event id in response")
    }

    /** Overwrite an existing event. [CalException] with 404/410 → gone. */
    fun updateEvent(
        token: String,
        eventId: String,
        title: String,
        startMillis: Long,
        endMillis: Long
    ) {
        val (code, body) = request(
            "$PRIMARY_EVENTS_URL/${URLEncoder.encode(eventId, "UTF-8")}",
            "PUT", token, eventJson(title, startMillis, endMillis)
        )
        if (code !in 200..299) throw CalException(code, body)
    }

    /** Delete an event; 404/410 (already gone) is success. */
    fun deleteEvent(token: String, eventId: String) {
        val (code, body) = request(
            "$PRIMARY_EVENTS_URL/${URLEncoder.encode(eventId, "UTF-8")}",
            "DELETE", token
        )
        if (code !in 200..299 && code != 404 && code != 410) throw CalException(code, body)
    }

    // ------------------------------------------------------------ helpers

    private fun eventJson(title: String, startMillis: Long, endMillis: Long): String =
        buildJsonObject {
            put("summary", title)
            put("description", "Synced from Nudge")
            putJsonObject("start") {
                put(
                    "dateTime",
                    DateTimeFormatter.ISO_OFFSET_DATE_TIME
                        .format(Instant.ofEpochMilli(startMillis).atZone(zone))
                )
                put("timeZone", zone.id)
            }
            putJsonObject("end") {
                put(
                    "dateTime",
                    DateTimeFormatter.ISO_OFFSET_DATE_TIME
                        .format(Instant.ofEpochMilli(endMillis).atZone(zone))
                )
                put("timeZone", zone.id)
            }
        }.toString()

    private fun request(
        url: String,
        method: String,
        token: String,
        body: String? = null
    ): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return code to text
        } finally {
            conn.disconnect()
        }
    }
}
