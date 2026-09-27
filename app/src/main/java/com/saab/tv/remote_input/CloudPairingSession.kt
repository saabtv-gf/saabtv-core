package com.saab.tv.remote_input

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.saab.tv.data.account.AccountAuthManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** No local listener or certificate. Secrets exist only for this dialog's lifetime. */
internal class CloudPairingSession(private val mode: String, private val manifest: JsonObject = JsonObject(),
    private val onMessage: (JsonObject) -> Unit) {
    companion object { const val PAGE_URL = "https://saabtv-gf.github.io/saabtv-core/" }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private val id = UUID.randomUUID().toString()
    private val reader = PairingCrypto.encode(PairingCrypto.secret())
    private val writer = PairingCrypto.encode(PairingCrypto.secret())
    private val key = PairingCrypto.secret()
    private var token = ""
    private var job: Job? = null
    @Volatile private var closed = false
    val status = MutableStateFlow("Connecting securely…")
    val url: String get() = "$PAGE_URL#id=$id&cap=$writer&key=${PairingCrypto.encode(key)}"

    suspend fun open(): Unit = withContext(Dispatchers.IO) {
        com.saab.tv.AppDiagnostics.event("Pairing", "Opening", "mode=$mode")
        try {
            val authOrigin = AccountAuthManager.AUTH_URL.substringBefore("/neondb/")
            token = JsonParser.parseString(request("${AccountAuthManager.AUTH_URL}/token/anonymous", origin = authOrigin))
                .asJsonObject.get("token").asString
            manifest.addProperty("mode", mode)
            manifest.addProperty("version", 1)
            rpc("create", JsonObject().apply {
                addProperty("session_id", id); addProperty("reader_hash", PairingCrypto.hash(reader))
                addProperty("writer_hash", PairingCrypto.hash(writer)); addProperty("tool", mode)
                addProperty("encrypted_manifest", PairingCrypto.encrypt(key, manifest.toString(), "$id|manifest"))
            })
            currentCoroutineContext().ensureActive()
            check(!closed)
            status.value = "Ready · QR expires in five minutes"
            com.saab.tv.AppDiagnostics.event("Pairing", "Ready", "mode=$mode")
            job = scope.launch { poll() }
        } catch (e: Exception) {
            if (e !is CancellationException) com.saab.tv.AppDiagnostics.failure("Pairing", "Open Failed", e)
            status.value = "Secure pairing unavailable. Check your internet connection and try again."
            withContext(NonCancellable + Dispatchers.IO) { closeRemote() }
            key.fill(0)
            throw e
        }
    }

    private suspend fun poll() {
        var ack = 0
        val deadline = android.os.SystemClock.elapsedRealtime() + 5 * 60_000L
        var failures = 0
        try {
            while (currentCoroutineContext().isActive && !closed && android.os.SystemClock.elapsedRealtime() < deadline) {
                try {
                    val response = rpc("read", owner().apply { addProperty("ack", ack) }).asJsonObject
                    val ciphertext = response.get("ciphertext")
                    if (ciphertext != null && !ciphertext.isJsonNull) {
                        val sequence = response.get("sequence").asInt
                        require(sequence == ack + 1)
                        val messageId = UUID.fromString(response.get("message_id").asString).toString()
                        val message = JsonParser.parseString(PairingCrypto.decrypt(key, ciphertext.asString,
                            "$id|message|$messageId")).asJsonObject
                        require(message.get("mode").asString == mode)
                        // Receipt callbacks may dismiss this dialog and cancel our job.
                        // Commit the acknowledgement before final cleanup in that case.
                        withContext(NonCancellable) {
                            withContext(Dispatchers.Main) {
                                if (closed) throw CancellationException("Pairing closed")
                                onMessage(message)
                            }
                            ack = sequence
                            rpc("read", owner().apply { addProperty("ack", ack) })
                        }
                        status.value = "Received securely"
                        com.saab.tv.AppDiagnostics.event("Pairing", "Message Received", "mode=$mode")
                        if (mode != "hub") break
                    }
                    failures = 0
                } catch (e: CancellationException) { throw e }
                catch (failure: Exception) {
                    com.saab.tv.AppDiagnostics.failure("Pairing", "Poll Failed", failure)
                    failures++
                    status.value = "Connection interrupted · retrying…"
                    if (failures >= 5) break
                }
                delay(if (failures == 0) 2500 else 5000)
            }
        } finally {
            com.saab.tv.AppDiagnostics.event("Pairing", "Closed", "mode=$mode")
            status.value = "Pairing closed. Reopen for a new QR code."
            withContext(NonCancellable + Dispatchers.IO) { closeRemote() }
            key.fill(0)
        }
    }

    fun close() { closed = true; job?.cancel(); scope.cancel() }
    private fun owner() = JsonObject().apply { addProperty("session_id", id); addProperty("capability", reader) }
    private fun closeRemote() { if (token.isNotEmpty()) runCatching { rpc("close", owner()) } }
    private fun rpc(name: String, body: JsonObject) = JsonParser.parseString(
        request("${AccountAuthManager.DATA_URL}/rpc/saabtv_pair_$name", body))
    private fun request(url: String, body: JsonObject? = null, origin: String? = null): String {
        val builder = Request.Builder().url(url)
        origin?.let { builder.header("Origin", it) }
        if (body != null) builder.header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Pairing request failed (${response.code})")
            return response.body?.string() ?: throw IOException("Empty pairing response")
        }
    }
}
