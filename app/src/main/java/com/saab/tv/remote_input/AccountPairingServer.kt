package com.saab.tv.remote_input

import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.saab.tv.data.account.AccountCredentials
import fi.iki.elonen.NanoHTTPD
import java.math.BigInteger
import java.net.InetAddress
import java.net.URLDecoder
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.X509KeyManager
import javax.security.auth.x500.X500Principal

/** Short-lived, TLS-only LAN pairing. Never reuses the plaintext remote-paste server. */
class AccountPairingServer(private val signup: Boolean,
    private val onCredentials: (String, String, Boolean) -> Unit) : NanoHTTPD(0) {
    private val alias = "saab-tv-pairing-" + UUID.randomUUID()
    private val token = UUID.randomUUID().toString() + UUID.randomUUID()
    private val created = android.os.SystemClock.elapsedRealtime()
    private val submitted = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private val expiry = Runnable { close() }
    private var delivery: Runnable? = null
    private var store: KeyStore? = null
    lateinit var url: String
        private set
    lateinit var fingerprint: String
        private set

    fun open() {
        val ip = NetworkUtils.getLocalIpAddress()
            ?: error("Connect the TV and phone to the same Wi-Fi first.")
        require(InetAddress.getByName(ip).isSiteLocalAddress) { "A private Wi-Fi or Ethernet connection is required." }
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            store = keyStore
            // Remove certificates left by an interrupted process; no account credentials are stored.
            keyStore.aliases().toList().filter { it.startsWith("saab-tv-pairing-") }.forEach(keyStore::deleteEntry)
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setCertificateSubject(X500Principal("CN=Saab TV Temporary Sign In"))
                    .setCertificateSerialNumber(BigInteger(128, SecureRandom()).abs().add(BigInteger.ONE))
                    .setCertificateNotBefore(Date(System.currentTimeMillis() - 60_000))
                    .setCertificateNotAfter(Date(System.currentTimeMillis() + 10 * 60_000)).build())
                generateKeyPair()
            }
            val key = keyStore.getKey(alias, null) as PrivateKey
            val chain = keyStore.getCertificateChain(alias).map { it as X509Certificate }.toTypedArray()
            fingerprint = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded)
                .joinToString(":") { "%02X".format(it.toInt() and 255) }
            val manager = object : X509KeyManager {
                override fun getClientAliases(k: String?, i: Array<java.security.Principal>?) = null
                override fun chooseClientAlias(k: Array<String>?, i: Array<java.security.Principal>?, s: java.net.Socket?) = null
                override fun getServerAliases(k: String?, i: Array<java.security.Principal>?) = if (k == "RSA") arrayOf(alias) else null
                override fun chooseServerAlias(k: String?, i: Array<java.security.Principal>?, s: java.net.Socket?) = if (k == "RSA") alias else null
                override fun getCertificateChain(a: String?) = if (a == alias) chain else null
                override fun getPrivateKey(a: String?) = if (a == alias) key else null
            }
            val ssl = SSLContext.getInstance("TLS").apply { init(arrayOf(manager), null, SecureRandom()) }
            makeSecure(ssl.serverSocketFactory, arrayOf("TLSv1.2"))
            start(10_000, true)
            url = "https://$ip:$listeningPort/$token"
            handler.postDelayed(expiry, 5 * 60_000)
        } catch (e: Exception) { close(); throw e }
    }

    fun close() {
        closed.set(true)
        handler.removeCallbacks(expiry)
        delivery?.let(handler::removeCallbacks)
        delivery = null
        stop()
        runCatching { store?.deleteEntry(alias) }
        store = null
    }

    override fun serve(session: IHTTPSession): Response {
        if (closed.get() || android.os.SystemClock.elapsedRealtime() - created > 5 * 60_000 || session.uri != "/$token")
            return response(Response.Status.NOT_FOUND, "This sign-in link has expired. Open a new QR code on the TV.")
        if (session.method == Method.GET) return response(Response.Status.OK, form(), true)
        if (session.method != Method.POST) return response(Response.Status.METHOD_NOT_ALLOWED, "Method not allowed")
        if (session.headers["origin"] != url.substringBeforeLast('/') ||
            !session.headers["content-type"].orEmpty().startsWith("application/x-www-form-urlencoded"))
            return response(Response.Status.FORBIDDEN, "Request rejected")
        val size = session.headers["content-length"]?.toIntOrNull()
        if (size == null || size !in 1..4096) return response(Response.Status.BAD_REQUEST, "Invalid request")
        return try {
            val bytes = ByteArray(size)
            var count = 0
            while (count < size) {
                val n = session.inputStream.read(bytes, count, size - count)
                require(n > 0)
                count += n
            }
            val fields = bytes.toString(Charsets.UTF_8).split('&').associate {
                val parts = it.split('=', limit = 2)
                URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
            }
            bytes.fill(0)
            val username = fields["username"].orEmpty()
            val password = fields["password"].orEmpty()
            require(fields["token"] == token)
            val problem = AccountCredentials.usernameError(username) ?: when {
                password.isEmpty() || password.length > 128 -> "Enter your password."
                signup -> AccountCredentials.passwordError(password)
                    ?: if (fields["confirm"] != password) "Passwords do not match." else null
                else -> null
            }
            if (problem != null) response(Response.Status.BAD_REQUEST, problem)
            else if (!submitted.compareAndSet(false, true)) response(Response.Status.CONFLICT, "Already sent. Continue on your TV.")
            else {
                delivery = Runnable { if (!closed.get()) onCredentials(username, password, signup); delivery = null }
                handler.postDelayed(delivery!!, 400)
                response(Response.Status.OK, "Details sent securely. Confirm the account on your TV to continue. You can close this page.")
            }
        } catch (_: Exception) { response(Response.Status.BAD_REQUEST, "Unable to read sign-in details. Open a new QR code on the TV.") }
    }

    private fun response(status: Response.Status, body: String, html: Boolean = false): Response =
        newFixedLengthResponse(status, if (html) MIME_HTML else MIME_PLAINTEXT, body).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("Referrer-Policy", "no-referrer")
            addHeader("X-Content-Type-Options", "nosniff")
            addHeader("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'")
        }

    private fun form() = """
        <!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Saab TV · ${if (signup) "Create Account" else "Sign In"}</title>
        <style>body{background:#07101f;color:#f4f7ff;font:16px system-ui;margin:0;padding:24px}main{max-width:420px;margin:8vh auto}h1{font-size:28px}p{color:#b5c1d5;line-height:1.5}label{display:block;margin:20px 0 8px}input,button{box-sizing:border-box;width:100%;padding:14px;border-radius:10px;font:inherit}input{background:#132238;color:white;border:1px solid #536880}button{margin-top:16px;border:0;background:#67c8ff;color:#07101f;font-weight:600}.toggle{background:#22344e;color:white}small{display:block;margin-top:10px;color:#b5c1d5}</style>
        <main><h1>Saab TV</h1><p>${if (signup) "Create your account" else "Sign in"} securely on your TV. Your TV will ask you to confirm before continuing.</p>
        <form method="post"><input type="hidden" name="token" value="$token">
        <label for="u">Username</label><input id="u" name="username" minlength="3" maxlength="32" pattern="[A-Za-z0-9_]{3,32}" autocomplete="username" required>
        <label for="p">Password</label><input id="p" name="password" type="password" maxlength="128" autocomplete="${if (signup) "new-password" else "current-password"}" required>
        <button class="toggle" type="button" onclick="let p=document.getElementById('p');p.type=p.type==='password'?'text':'password';this.textContent=p.type==='password'?'Show Password':'Hide Password'">Show Password</button>
        ${if (signup) "<small>8+ characters with uppercase, lowercase, a number and a symbol. Username availability is checked when you confirm on the TV.</small><label for='c'>Confirm Password</label><input id='c' name='confirm' type='password' maxlength='128' autocomplete='new-password' required>" else ""}
        <button type="submit">Continue On TV</button></form><small>This private link expires after 5 minutes. No password is saved by this page.</small></main></html>
    """.trimIndent()
}
