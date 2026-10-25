package com.dev.nudge.data.backup

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * ☁️ Google Drive backup transport (spec §9).
 *
 * Auth uses the modern [com.google.android.gms.auth.api.identity.Identity]
 * AuthorizationClient — NOT the deprecated GoogleSignInClient flow.
 * Storage is Drive's hidden `appDataFolder`: per-app, per-user, never
 * visible in the user's My Drive, no `drive.file` consent screen needed.
 *
 * Error taxonomy is mapped onto [DriveErrorKind] so the UI can distinguish
 * "offline" from "expired auth" from "quota" from "transient — try again".
 */
object DriveBackupClient {

    /** Visible in logcat so backup failures can be diagnosed (`NudgeDrive`). */
    private const val TAG = "NudgeDrive"

    private const val APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
    private const val EMAIL_SCOPE = "https://www.googleapis.com/auth/userinfo.email"
    const val FILE_NAME = "nudge-backup.json"

    private const val FILES_URL = "https://www.googleapis.com/drive/v3/files"
    private const val UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"
    private const val USERINFO_URL = "https://www.googleapis.com/oauth2/v3/userinfo"

    private val json = Json { ignoreUnknownKeys = true }

    enum class DriveErrorKind { OFFLINE, AUTH, QUOTA, NOT_FOUND, TRANSIENT, UNKNOWN }

    sealed interface DriveAuth {
        data class Authorized(val accessToken: String) : DriveAuth
        /** User must consent in a system dialog — launch this PendingIntent. */
        data class NeedsResolution(val pendingIntent: PendingIntent) : DriveAuth
        data class Failed(val reason: String) : DriveAuth
    }

    sealed interface DriveOpResult {
        /** [body] carries the downloaded JSON on restore, null on upload. */
        data class Success(val body: String? = null, val email: String? = null) : DriveOpResult
        data class NeedsAuth(val pendingIntent: PendingIntent) : DriveOpResult
        data class Error(val kind: DriveErrorKind, val detail: String) : DriveOpResult
    }

    private class HttpException(val code: Int, val body: String) :
        Exception("HTTP $code: ${body.take(200)}")

    // ------------------------------------------------------------- auth

    private fun buildRequest(): AuthorizationRequest =
        AuthorizationRequest.Builder()
            .setRequestedScopes(listOf(Scope(APPDATA_SCOPE), Scope(EMAIL_SCOPE)))
            .build()

    /** Silent when already granted; NeedsResolution on first use / new scope. */
    suspend fun authorize(context: Context): DriveAuth {
        return try {
            val result = Identity.getAuthorizationClient(context)
                .authorize(buildRequest())
                .await()
            mapResult(result).also {
                Log.d(TAG, "authorize -> " +
                    (if (it is DriveAuth.NeedsResolution) "consent required" else it::class.simpleName))
            }
        } catch (e: Exception) {
            Log.w(TAG, "authorize threw: ${e.message}")
            DriveAuth.Failed(e.message ?: "authorization failed")
        }
    }

    /** Extract the consent result from the resolution Intent the UI launched. */
    fun authFromIntent(context: Context, data: Intent?): DriveAuth {
        if (data == null) return DriveAuth.Failed("authorization cancelled")
        return try {
            val result = Identity.getAuthorizationClient(context)
                .getAuthorizationResultFromIntent(data)
            mapResult(result)
        } catch (e: Exception) {
            DriveAuth.Failed(e.message ?: "authorization failed")
        }
    }

    private fun mapResult(result: com.google.android.gms.auth.api.identity.AuthorizationResult): DriveAuth {
        val token = result.accessToken
        val pending = result.pendingIntent
        return when {
            result.hasResolution() && pending != null -> DriveAuth.NeedsResolution(pending)
            result.hasResolution() -> DriveAuth.Failed("missing resolution intent")
            !token.isNullOrBlank() -> DriveAuth.Authorized(token)
            else -> DriveAuth.Failed("no access token")
        }
    }

    // --------------------------------------------------------- operations

    suspend fun upload(context: Context, payload: String): DriveOpResult =
        runOp(context) { token -> doUpload(token, payload); null }

    suspend fun download(context: Context): DriveOpResult =
        runOp(context) { token -> doDownload(token) }

    /** Logs the final outcome — without this, failures were invisible. */
    private suspend fun runOp(
        context: Context,
        op: suspend (token: String) -> String?
    ): DriveOpResult {
        val result = driveOp(context, op)
        Log.d(TAG, when (result) {
            is DriveOpResult.Success ->
                "op ok (email=${result.email ?: "-"}, hasBody=${result.body != null})"
            is DriveOpResult.NeedsAuth -> "op waiting for consent"
            is DriveOpResult.Error -> "op FAILED ${result.kind}: ${result.detail.take(300)}"
        })
        return result
    }

    private suspend fun driveOp(
        context: Context,
        op: suspend (token: String) -> String?
    ): DriveOpResult {
        var token = when (val auth = authorize(context)) {
            is DriveAuth.Authorized -> auth.accessToken
            is DriveAuth.NeedsResolution -> return DriveOpResult.NeedsAuth(auth.pendingIntent)
            is DriveAuth.Failed -> return DriveOpResult.Error(
                DriveErrorKind.AUTH, auth.reason
            )
        }
        var reauthorized = false

        return try {
            val body = op(token)
            DriveOpResult.Success(body = body, email = fetchEmail(token))
        } catch (e: HttpException) {
            when {
                e.code == 401 && !reauthorized -> {
                    reauthorized = true
                    val retryToken = when (val auth = authorize(context)) {
                        is DriveAuth.Authorized -> auth.accessToken
                        is DriveAuth.NeedsResolution ->
                            return DriveOpResult.NeedsAuth(auth.pendingIntent)
                        is DriveAuth.Failed ->
                            return DriveOpResult.Error(DriveErrorKind.AUTH, auth.reason)
                    }
                    token = retryToken
                    try {
                        val body = op(retryToken)
                        DriveOpResult.Success(body = body, email = fetchEmail(retryToken))
                    } catch (e2: Exception) { mapHttp(e2) }
                }
                e.code == 404 -> DriveOpResult.Error(DriveErrorKind.NOT_FOUND, "no backup")
                e.code == 401 || e.code == 403 ->
                    if (e.body.contains("storageQuota")) {
                        DriveOpResult.Error(DriveErrorKind.QUOTA, "storage quota exceeded")
                    } else {
                        DriveOpResult.Error(DriveErrorKind.AUTH, "HTTP ${e.code}")
                    }
                e.code == 429 || e.code >= 500 ->
                    DriveOpResult.Error(DriveErrorKind.TRANSIENT, "HTTP ${e.code}")
                else -> DriveOpResult.Error(DriveErrorKind.UNKNOWN, "HTTP ${e.code}")
            }
        } catch (e: IOException) {
            DriveOpResult.Error(DriveErrorKind.OFFLINE, e.message ?: "network error")
        } catch (e: Exception) {
            mapHttp(e)
        }
    }

    private fun mapHttp(e: Exception): DriveOpResult.Error = when (e) {
        is HttpException -> when {
            e.code == 404 -> DriveOpResult.Error(DriveErrorKind.NOT_FOUND, "no backup")
            e.code == 401 || e.code == 403 ->
                DriveOpResult.Error(DriveErrorKind.AUTH, "HTTP ${e.code}")
            else -> DriveOpResult.Error(DriveErrorKind.UNKNOWN, "HTTP ${e.code}")
        }
        is IOException -> DriveOpResult.Error(DriveErrorKind.OFFLINE, e.message ?: "network")
        else -> DriveOpResult.Error(DriveErrorKind.UNKNOWN, e.message ?: "error")
    }

    // ------------------------------------------------------ Drive REST v3

    private suspend fun doUpload(token: String, payload: String) {
        val existing = findFileId(token)
        if (existing != null) {
            val (code, body) = request(
                "$UPLOAD_URL/$existing?uploadType=media", "PATCH", token,
                contentType = "application/json; charset=UTF-8",
                body = payload.toByteArray(Charsets.UTF_8)
            )
            if (code !in 200..299) throw HttpException(code, body)
        } else {
            val meta = """{"name":"$FILE_NAME","parents":["appDataFolder"]}"""
            val (code, body) = request(
                FILES_URL, "POST", token,
                contentType = "application/json; charset=UTF-8",
                body = meta.toByteArray(Charsets.UTF_8)
            )
            if (code !in 200..299) throw HttpException(code, body)
            val id = json.parseToJsonElement(body).jsonObject["id"]
                ?.jsonPrimitive?.contentOrNull
                ?: throw HttpException(code, "missing file id")
            val (c2, b2) = request(
                "$UPLOAD_URL/$id?uploadType=media", "PATCH", token,
                contentType = "application/json; charset=UTF-8",
                body = payload.toByteArray(Charsets.UTF_8)
            )
            if (c2 !in 200..299) throw HttpException(c2, b2)
        }
    }

    private suspend fun doDownload(token: String): String {
        val id = findFileId(token) ?: throw HttpException(404, "no backup file")
        val (code, body) = request("$FILES_URL/$id?alt=media", "GET", token)
        if (code !in 200..299) throw HttpException(code, body)
        return body
    }

    private suspend fun findFileId(token: String): String? {
        val q = URLEncoder.encode(
            "name='$FILE_NAME' and 'appDataFolder' in parents", "UTF-8"
        )
        val (code, body) = request(
            "$FILES_URL?spaces=appDataFolder&q=$q&fields=files(id)", "GET", token
        )
        if (code !in 200..299) throw HttpException(code, body)
        val files = json.parseToJsonElement(body).jsonObject["files"]?.jsonArray
            ?: return null
        return files.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
    }

    /** Best-effort account email for the Settings header; null is fine. */
    private suspend fun fetchEmail(token: String): String? = try {
        val (code, body) = request(USERINFO_URL, "GET", token)
        if (code in 200..299) {
            json.parseToJsonElement(body).jsonObject["email"]
                ?.jsonPrimitive?.contentOrNull
        } else null
    } catch (e: Exception) {
        null
    }

    // ------------------------------------------------------------ HTTP

    /**
     * One HTTP round-trip with retry + backoff for transient failures
     * (429/5xx/IO errors): up to 3 attempts. 4xx codes are returned to the
     * caller for taxonomy mapping.
     */
    private suspend fun request(
        url: String,
        method: String,
        token: String,
        contentType: String? = null,
        body: ByteArray? = null
    ): Pair<Int, String> {
        val backoff = longArrayOf(500L, 1_500L, 3_000L)
        var lastError: Exception? = null

        repeat(3) { attempt ->
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    doOutput = body != null
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("Accept", "application/json")
                    contentType?.let { setRequestProperty("Content-Type", it) }
                }
                if (body != null) conn.outputStream.use { it.write(body) }
                val code = conn.responseCode
                Log.d(TAG, "$method ${url.substringBefore('?')} -> $code")
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                conn.disconnect()

                val transient = code == 429 || code in 500..599
                if (transient && attempt < 2) {
                    lastError = IOException("HTTP $code")
                    delay(backoff[attempt])
                    return@repeat
                }
                return code to text
            } catch (e: IOException) {
                lastError = e
                Log.w(TAG, "$method ${url.substringBefore('?')} io error (attempt $attempt): ${e.message}")
                if (attempt < 2) {
                    delay(backoff[attempt])
                    return@repeat
                }
            }
        }
        throw lastError ?: IOException("request failed")
    }
}