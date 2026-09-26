package com.saab.tv.remote_input

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

/** No Neon requests or QA accounts: checks only the local encrypted transport. */
@RunWith(AndroidJUnit4::class)
class AccountPairingServerTest {
    @Test fun tlsFormRejectsCrossOriginAndDeliversOnlyOnce() {
        val delivered = CountDownLatch(1)
        var received: String? = null
        val server = AccountPairingServer(false) { username, _, signup ->
            assertFalse(signup)
            received = username
            delivered.countDown()
        }
        try {
            server.open()
            val trust = object : X509TrustManager {
                override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
                override fun checkClientTrusted(c: Array<X509Certificate>, a: String) = error("Not a client certificate")
                override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {
                    val hash = MessageDigest.getInstance("SHA-256").digest(c.first().encoded)
                        .joinToString(":") { "%02X".format(it.toInt() and 255) }
                    require(hash == server.fingerprint)
                }
            }
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
            fun connect(): HttpsURLConnection = (URL(server.url).openConnection() as HttpsURLConnection).apply {
                sslSocketFactory = ssl.socketFactory
                // The test pins the exact ephemeral certificate above; production browsers show a warning.
                hostnameVerifier = HostnameVerifier { host, _ -> host == URL(server.url).host }
                connectTimeout = 5000; readTimeout = 5000
            }
            val get = connect()
            val html = try {
                assertEquals(200, get.responseCode)
                assertEquals("no-store", get.getHeaderField("Cache-Control"))
                get.inputStream.bufferedReader().use { it.readText() }
            } finally { get.disconnect() }
            val token = Regex("name=\"token\" value=\"([^\"]+)\"").find(html)!!.groupValues[1]
            fun post(origin: String): Int {
                val connection = connect()
                return try {
                    connection.requestMethod = "POST"; connection.doOutput = true
                    connection.setRequestProperty("Origin", origin)
                    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    val body = "username=test_pairing&password=Example1%21&token=" + URLEncoder.encode(token, "UTF-8")
                    connection.outputStream.use { it.write(body.toByteArray()) }
                    connection.responseCode
                } finally { connection.disconnect() }
            }
            assertEquals(403, post("https://untrusted.invalid"))
            assertEquals(200, post(server.url.substringBeforeLast('/')))
            assertTrue(delivered.await(5, TimeUnit.SECONDS))
            assertEquals("test_pairing", received)
            assertEquals(409, post(server.url.substringBeforeLast('/')))
        } finally { server.close() }
    }
}
