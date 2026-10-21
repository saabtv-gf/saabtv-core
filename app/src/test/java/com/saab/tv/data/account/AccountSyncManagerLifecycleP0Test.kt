package com.saab.tv.data.account

import android.app.Application
import com.saab.tv.testing.AccountTransportFixture
import com.saab.tv.testing.OfflineAppFixture
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AccountSyncManagerLifecycleP0Test {
    @Test fun manualReconcileStartsTheSameCooldownAsAutomaticSync() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val transport = AccountTransportFixture(context)
        val app = OfflineAppFixture(context)
        transport.handler = { request ->
            when {
                request.url.encodedPath.endsWith("saabtv_sync_version") -> 200 to "3"
                request.url.encodedPath.endsWith("saabtv_sync_v3_pull") -> 429 to "{\"code\":\"PT429\"}"
                request.url.encodedPath.endsWith("saabtv_account_state") -> 200 to "[]"
                else -> error("Unexpected sync request: ${request.url.encodedPath}")
            }
        }
        val manager = AccountSyncManager(context, transport.auth, app.db, app.configuration)
        try {
            try { manager.syncAndReconcile(); org.junit.Assert.fail("Expected server throttle") }
            catch (error: AccountApiException) { assertEquals(429, error.status) }
            val requests = transport.requests.size
            manager.historyChanged(urgent = true)
            assertFalse(manager.syncAndReconcile())
            assertFalse(manager.syncNow())
            assertEquals(requests, transport.requests.size)
        } finally { manager.stop(); app.close() }
    }

    @Test fun rateLimitedSyncDoesNotSendAgainAfterUrgentChangeOrManualRetry() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val transport = AccountTransportFixture(context)
        val app = OfflineAppFixture(context)
        transport.handler = { request ->
            when {
                request.url.encodedPath.endsWith("saabtv_sync_version") -> 200 to "3"
                request.url.encodedPath.endsWith("saabtv_sync_v3_pull") -> 429 to "{\"code\":\"PT429\"}"
                request.url.encodedPath.endsWith("saabtv_account_state") -> 200 to "[]"
                else -> error("Unexpected sync request: ${request.url.encodedPath}")
            }
        }
        val manager = AccountSyncManager(context, transport.auth, app.db, app.configuration)
        try {
            assertFalse(manager.syncNow())
            assertEquals(1, transport.requests.count { it.url.encodedPath.endsWith("saabtv_sync_v3_pull") })
            val requestsAfterThrottle = transport.requests.size
            manager.historyChanged(urgent = true)
            manager.flushAfterBackground()
            assertFalse(manager.syncNow())
            assertFalse(manager.syncAndReconcile())
            assertEquals(requestsAfterThrottle, transport.requests.size)
        } finally {
            manager.stop()
            app.close()
        }
    }

    @Test fun stoppedManagerIgnoresLaterHistoryAndBackgroundFlushRequests() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val transport = AccountTransportFixture(context)
        val app = OfflineAppFixture(context)
        val manager = AccountSyncManager(context, transport.auth, app.db, app.configuration)
        try {
            manager.stop()
            manager.historyChanged(urgent = true)
            manager.flushAfterBackground()

            assertFalse(manager.syncNow())
            assertTrue("Logout must not start another cloud request", transport.requests.isEmpty())
        } finally {
            manager.stop()
            app.close()
        }
    }
}
