package com.saab.tv.data.account

import android.app.Application
import com.saab.tv.testing.AccountTransportFixture
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import okio.Buffer

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], application=Application::class)
class AccountAuthP1Test {
    private fun fixture(signedIn: Boolean=true)=AccountTransportFixture(RuntimeEnvironment.getApplication(),signedIn)
    @Test fun invalidSignupInputIsRejectedBeforeNetwork() = runBlocking {
        val f=fixture(false)
        listOf("a" to "Aa1!valid", "valid" to "weak").forEach { (user,password) ->
            try { f.auth.authenticate(user,password,true); fail("Invalid input accepted") } catch (_: IllegalArgumentException) {}
        }
        assertTrue(f.requests.isEmpty()); assertFalse(f.auth.hasSession)
    }
    @Test fun availabilityNormalizesUsernameAndReusesAnonymousToken() = runBlocking {
        val f=fixture(false)
        f.handler={ request -> if(request.url.encodedPath.endsWith("/anonymous")) 200 to "{\"token\":\"anonymous\"}" else 200 to "true" }
        assertTrue(f.auth.usernameAvailable(" Tester ")); assertTrue(f.auth.usernameAvailable("Another"))
        assertEquals(1,f.requests.count { it.url.encodedPath.endsWith("/anonymous") })
        val requests=f.requests.filter { it.url.encodedPath.endsWith("saabtv_username_available") }
        val body=Buffer(); requests.first().body!!.writeTo(body)
        assertTrue(body.readUtf8().contains("tester")); assertEquals("Bearer anonymous",requests.first().header("Authorization"))
    }
    @Test fun jwtIsReusedAndDataRequestUsesBearerNotCookie() = runBlocking {
        val f=fixture(); f.handler={ 200 to "[]" }
        f.auth.dataRequest("fixture"); f.auth.dataRequest("fixture")
        assertEquals(1,f.requests.count { it.url.encodedPath.endsWith("/token") })
        f.requests.filter { it.url.encodedPath.endsWith("/fixture") }.forEach {
            assertEquals("Bearer fixture-token",it.header("Authorization")); assertNull(it.header("Cookie"))
        }
    }
    @Test fun wrongAccountSessionIsClearedRatherThanCrossAccountRestore() = runBlocking {
        val f=fixture(); f.handler={200 to "{\"user\":{\"id\":\"other-user\"}}"}
        assertFalse(f.auth.validateSession()); assertFalse(f.auth.hasSession); assertNull(f.auth.userId)
    }
    @Test fun unauthorizedSessionClearsButTransientFailurePreservesLogin() = runBlocking {
        val f=fixture(); f.handler={503 to "{}"}
        try { f.auth.validateSession(); fail("Failure swallowed") } catch (_: AccountApiException) {}
        assertTrue(f.auth.hasSession); f.handler={401 to "{}"}
        assertFalse(f.auth.validateSession()); assertFalse(f.auth.hasSession)
    }
    @Test fun offlineSignOutAlwaysClearsLocalSecrets() = runBlocking {
        val f=fixture(); f.handler={throw java.io.IOException("Offline")}
        f.auth.signOut(); assertFalse(f.auth.hasSession); assertTrue(f.prefs.all.isEmpty())
    }
    @Test fun signedOutValidationDoesNotRequestNetworkAndJwtFailsSafely() = runBlocking {
        val f=fixture(false); assertFalse(f.auth.validateSession())
        try { f.auth.jwt(); fail("Token issued without session") } catch (_: java.io.IOException) {}
        assertTrue(f.requests.isEmpty())
    }
    @Test fun failedLoginNeverPersistsPartialSession() = runBlocking {
        val f=fixture(false); f.handler={401 to "{}"}
        try { f.auth.authenticate("tester","Wrong1!",false); fail("Bad login accepted") } catch (_: AccountApiException) {}
        assertFalse(f.auth.hasSession); assertTrue(f.prefs.all.isEmpty())
    }
}
