package com.saab.tv.data.account

import android.app.Application
import com.saab.tv.testing.AccountTransportFixture
import com.saab.tv.testing.OfflineAppFixture
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
