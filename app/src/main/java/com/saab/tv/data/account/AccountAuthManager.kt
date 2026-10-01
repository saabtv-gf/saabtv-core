package com.saab.tv.data.account

import android.content.Context
import android.util.Base64
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.saab.tv.data.security.SecurePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

class AccountApiException(val status: Int, message: String) : IOException(message)

@Singleton
class AccountAuthManager @Inject constructor(@ApplicationContext private val context: Context) {
    companion object {
        const val AUTH_URL = "https://ep-gentle-voice-azd9if2p.neonauth.c-3.ap-southeast-1.aws.neon.tech/neondb/auth"
        const val DATA_URL = "https://ep-gentle-voice-azd9if2p.apirest.c-3.ap-southeast-1.aws.neon.tech/neondb/rest/v1"
        private const val AUTH_ORIGIN = "https://ep-gentle-voice-azd9if2p.neonauth.c-3.ap-southeast-1.aws.neon.tech"
    }

    private val secure = SecurePreferences.create(context, "saabtv_account_session", "saabtv_account_session_key", accountScoped = false)
    private val prefs = secure.preferences
    private val mutex = Mutex()
    @Volatile private var anonymousToken: Pair<Long, String>? = null
    @Volatile private var accountToken: Triple<String, Long, String>? = null
    // Auth/data traffic must never inherit addon HTTP caches, redirects or logging.
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    val userId: String? get() = prefs.getString("user_id", null)
    val username: String get() = prefs.getString("username", "").orEmpty()
    val hasSession: Boolean get() = userId != null && !prefs.getString("cookie", null).isNullOrBlank()
    val syncKey: ByteArray get() = Base64.decode(prefs.getString("sync_key", null)
        ?: throw IOException("Please sign in again to unlock account sync."), Base64.NO_WRAP)

    init {
        // The encrypted session is authoritative. Repair the non-secret routing
        // marker if the process stopped between the two preference commits.
        AccountStorage.setUserId(context, if (hasSession) userId else null)
    }

    suspend fun usernameAvailable(username: String): Boolean = withContext(Dispatchers.IO) {
        require(AccountCredentials.usernameError(username) == null)
        val body = JsonObject().apply { addProperty("requested_username", AccountCredentials.normalizeUsername(username)) }
        val anonymous = anonymousToken?.takeIf { System.currentTimeMillis() - it.first < 5 * 60_000 }?.second
            ?: JsonParser.parseString(request("$AUTH_URL/token/anonymous")).asJsonObject.get("token").asString.also {
                anonymousToken = System.currentTimeMillis() to it
            }
        val response = request("$DATA_URL/rpc/saabtv_username_available", body, bearer = anonymous)
        JsonParser.parseString(response).asBoolean
    }

    suspend fun authenticate(username: String, password: String, signup: Boolean) = withContext(Dispatchers.IO) {
        com.saab.tv.AppDiagnostics.event(context, "Account", "Authentication Started", "signup=$signup")
        mutex.withLock {
            require(AccountCredentials.usernameError(username) == null)
            if (signup) require(AccountCredentials.passwordError(password) == null)
            check(secure.isPersistent) { "Secure device storage is unavailable. Login cannot be saved safely." }
            val normalized = AccountCredentials.normalizeUsername(username)
            val body = JsonObject().apply {
                addProperty("email", AccountCredentials.alias(normalized))
                addProperty("password", password)
                addProperty("rememberMe", true)
                if (signup) addProperty("name", normalized)
            }
            val endpoint = if (signup) "sign-up/email" else "sign-in/email"
            val request = Request.Builder().url("$AUTH_URL/$endpoint")
                .header("Origin", AUTH_ORIGIN)
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            client.newCall(request).execute().use { response ->
                com.saab.tv.AppDiagnostics.event(context, "Account", "Authentication Response", "code=${response.code}")
                val data = parseResponse(response.code, response.body?.string().orEmpty())
                val id = data.getAsJsonObject("user")?.get("id")?.asString
                    ?: throw IOException("Neon did not return an account ID.")
                val cookies = response.headers.values("Set-Cookie").map { it.substringBefore(';') }
                    .filter { it.startsWith("__Secure-neon-auth.session_token=") ||
                        it.startsWith("neon-auth.session_token=") || it.contains("auth.session_token=") }
                if (cookies.isEmpty()) throw IOException("No login session was returned. Check Neon email verification settings.")
                val key = AccountSnapshotCrypto.deriveKey(password.toCharArray(), id)
                check(prefs.edit().putString("user_id", id).putString("username", normalized)
                    .putString("cookie", cookies.joinToString("; "))
                    .putString("sync_key", Base64.encodeToString(key, Base64.NO_WRAP)).commit())
                key.fill(0)
                AccountStorage.setUserId(context, id)
                accountToken = null
                com.saab.tv.AppDiagnostics.event(context, "Account", "Authentication Completed")
            }
        }
    }

    suspend fun validateSession(): Boolean = withContext(Dispatchers.IO) {
        if (!hasSession) return@withContext false
        val json = try { request("$AUTH_URL/get-session", cookie = prefs.getString("cookie", null)) }
        catch (e: AccountApiException) {
            if (e.status == 401) { clearSession(); return@withContext false }
            throw e
        }
        val value = JsonParser.parseString(json)
        if (value.isJsonNull || value.asJsonObject.getAsJsonObject("user")?.get("id")?.asString != userId) {
            clearSession(); false
        } else true
    }

    suspend fun jwt(): String = withContext(Dispatchers.IO) {
        val id = userId ?: throw IOException("Please log in to use account sync.")
        accountToken?.takeIf { it.first == id && System.currentTimeMillis() - it.second < 5 * 60_000 }
            ?.let { return@withContext it.third }
        val value = request("$AUTH_URL/token", cookie = prefs.getString("cookie", null))
        val token = JsonParser.parseString(value).asJsonObject.get("token")?.asString
            ?: throw IOException("Unable to obtain a Neon access token.")
        accountToken = Triple(id, System.currentTimeMillis(), token)
        token
    }

    suspend fun dataRequest(path: String, body: JsonObject? = null, method: String = if (body == null) "GET" else "POST",
        extraHeaders: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        val sent = body?.toString()?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L
        try {
            request("$DATA_URL/$path", body, method = method, bearer = jwt(), extraHeaders = extraHeaders).also {
                AccountTransferMetrics.record(context, sent, it.toByteArray(Charsets.UTF_8).size.toLong(), true)
            }
        } catch (e: Exception) {
            AccountTransferMetrics.record(context, sent, 0, false)
            throw e
        }
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        try { request("$AUTH_URL/sign-out", JsonObject(), cookie = prefs.getString("cookie", null)) }
        catch (_: IOException) { /* Local logout must still work offline. */ }
        clearSession()
    }

    fun clearSession() {
        accountToken = null
        check(prefs.edit().clear().commit())
        AccountStorage.setUserId(context, null)
    }

    private fun request(url: String, body: JsonObject? = null, method: String = if (body == null) "GET" else "POST",
        cookie: String? = null, bearer: String? = null, extraHeaders: Map<String, String> = emptyMap()): String {
        val builder = Request.Builder().url(url).header("Accept", "application/json")
        if (url.startsWith("$AUTH_URL/")) builder.header("Origin", AUTH_ORIGIN)
        cookie?.let { builder.header("Cookie", it) }
        bearer?.let { builder.header("Authorization", "Bearer $it") }
        extraHeaders.forEach { (key, value) -> builder.header(key, value) }
        builder.method(method, body?.toString()?.toRequestBody("application/json".toMediaType()))
        return client.newCall(builder.build()).execute().use {
            val raw = it.body?.string().orEmpty()
            if (!it.isSuccessful) parseResponse(it.code, raw)
            if (url.startsWith("$AUTH_URL/") && cookie != null && it.isSuccessful) {
                // Managed Auth rotates session cookies during session refresh.
                val renewed = it.headers.values("Set-Cookie").map { header -> header.substringBefore(';') }
                    .filter { header -> header.contains("auth.session_token=") && header.substringAfter('=').isNotBlank() }
                if (renewed.isNotEmpty()) check(prefs.edit().putString("cookie", renewed.joinToString("; ")).commit())
            }
            raw
        }
    }

    private fun parseResponse(status: Int, raw: String): JsonObject {
        if (status !in 200..299) {
            // Never show/log raw service responses, credentials, URLs or tokens.
            val code = runCatching { JsonParser.parseString(raw).asJsonObject.get("code")?.asString }.getOrNull()
            val message = when {
                code == "PASSWORD_TOO_SHORT" -> "Neon requires a longer password. Use at least 8 characters."
                code == "USER_ALREADY_EXISTS" || status == 422 -> "This username already exists. Try logging in."
                status == 401 -> "Your login has expired or the username/password is incorrect."
                status == 403 -> "Neon denied access. Check authentication and database policies."
                status == 404 -> "Account sync is not configured in Neon yet."
                status == 409 || status == 412 -> "Another device changed this account. Reload before syncing."
                status == 429 -> "Too many attempts. Please wait and try again."
                else -> "Neon request failed (HTTP $status). Please try again."
            }
            throw AccountApiException(status, message)
        }
        return JsonParser.parseString(raw).asJsonObject
    }
}
