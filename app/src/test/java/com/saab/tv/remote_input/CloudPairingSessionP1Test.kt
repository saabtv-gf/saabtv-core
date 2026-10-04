package com.saab.tv.remote_input

import android.app.Application
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.saab.tv.testing.awaitAppState
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class CloudPairingSessionP1Test {
    private val calls=CopyOnWriteArrayList<Request>()
    private lateinit var session:CloudPairingSession
    private lateinit var key:ByteArray
    private lateinit var id:String
    private lateinit var messageId:String
    private var queuedMode="search"
    private var httpFailure=false
    private lateinit var testClient:OkHttpClient
    @Before fun setup() {
        session=CloudPairingSession("search",JsonObject().apply { addProperty("inputKind","text") }) {}
        val fragment=session.url.substringAfter('#').split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
        id=fragment.getValue("id"); key=PairingCrypto.decode(fragment.getValue("key")); messageId=UUID.randomUUID().toString()
        testClient=OkHttpClient.Builder().addInterceptor { chain ->
            val request=chain.request(); calls+=request
            val path=request.url.encodedPath
            val body=Buffer().also { request.body?.writeTo(it) }.readUtf8()
            val response=when {
                httpFailure -> 503 to "{}"
                path.endsWith("/token/anonymous") -> 200 to "{\"token\":\"anon\"}"
                path.endsWith("/saabtv_pair_create") -> {
                    val obj=JsonParser.parseString(body).asJsonObject
                    assertEquals(id,obj["session_id"].asString)
                    assertNotEquals(PairingCrypto.encode(key),obj["encrypted_manifest"].asString)
                    val manifest=JsonParser.parseString(PairingCrypto.decrypt(key,obj["encrypted_manifest"].asString,"$id|manifest")).asJsonObject
                    assertEquals("search",manifest["mode"].asString); assertEquals(1,manifest["version"].asInt)
                    200 to "{}"
                }
                path.endsWith("/saabtv_pair_read") -> {
                    val obj=JsonParser.parseString(body).asJsonObject
                    assertEquals(id,obj["session_id"].asString)
                    if(obj["ack"].asInt==0) {
                        val message=JsonObject().apply { addProperty("mode",queuedMode); addProperty("text","Search") }
                        val encrypted=PairingCrypto.encrypt(key,message.toString(),"$id|message|$messageId")
                        200 to JsonObject().apply { addProperty("sequence",1);addProperty("message_id",messageId);addProperty("ciphertext",encrypted) }.toString()
                    } else 200 to "{\"ciphertext\":null}"
                }
                path.endsWith("/saabtv_pair_close") -> 200 to "{}"
                else -> error("Unexpected pairing endpoint $path")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(response.first).message("fixture")
                .body(response.second.toResponseBody("application/json".toMediaType())).build()
        }.build()
        bindSessionClient()
    }
    @After fun cleanup() { session.close(); key.fill(0) }
    @Test fun createsCapabilitySessionDeliversOneBoundMessageAndAcknowledgesBeforeClose() = runBlocking {
        val received=AtomicReference<JsonObject?>()
        session=CloudPairingSession("search",JsonObject()) { received.set(it) }
        // The replacement instance has a new fragment; keep fixture identity/key synchronized.
        bindSessionClient()
        val fragment=session.url.substringAfter('#').split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
        id=fragment.getValue("id"); key=PairingCrypto.decode(fragment.getValue("key"))
        session.open()
        awaitAppState { received.get()!=null }
        val message=requireNotNull(received.get())
        assertEquals("Search",message["text"].asString)
        withTimeout(3000) { while(calls.none { it.url.encodedPath.endsWith("/saabtv_pair_read") && requestBody(it).contains("\"ack\":1") }) delay(5) }
        withTimeout(3000) {
            while (session.status.value != "Pairing closed. Reopen for a new QR code.") delay(5)
        }
        val create=calls.first { it.url.encodedPath.endsWith("/saabtv_pair_create") }
        assertEquals("Bearer anon",create.header("Authorization"))
        val anon=calls.first { it.url.encodedPath.endsWith("/token/anonymous") }
        assertTrue(anon.header("Origin")!!.startsWith("https://"))
    }
    @Test fun pairUrlKeepsCapabilityAndEncryptionKeyOutOfHttpQuery() {
        val parsed=android.net.Uri.parse(session.url)
        assertEquals("https",parsed.scheme);assertEquals("saabtv-gf.github.io",parsed.host)
        assertNull(parsed.query);assertTrue(parsed.fragment!!.contains("&key="));assertFalse(session.url.contains("?"))
    }
    @Test fun failedAnonymousConnectDoesNotCreateRemoteSessionOrExposeSecrets() = runBlocking {
        httpFailure=true
        try { session.open();fail("Open unexpectedly succeeded") } catch (_:Exception) {}
        assertTrue(session.status.value.contains("unavailable"))
        assertFalse(calls.any { it.url.encodedPath.endsWith("/saabtv_pair_create") })
        assertTrue(session.url.contains("#id="))
    }
    @Test fun wrongModeMessageIsRejectedAndDoesNotInvokeConsumer() = runBlocking {
        queuedMode="paste"; var received=0
        session=CloudPairingSession("search",JsonObject()) { received++ };bindSessionClient()
        val fragment=session.url.substringAfter('#').split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
        id=fragment.getValue("id");key=PairingCrypto.decode(fragment.getValue("key"))
        session.open()
        awaitAppState { session.status.value.contains("retrying") }
        session.close();delay(50)
        assertEquals(0,received)
    }
    private fun bindSessionClient() {
        CloudPairingSession::class.java.getDeclaredField("client").apply { isAccessible=true }.set(session,testClient)
    }
    private fun requestBody(request:Request)=Buffer().also { request.body?.writeTo(it) }.readUtf8()
}
