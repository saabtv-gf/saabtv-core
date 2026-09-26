package com.saab.tv.data.update

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

/** No network traffic or actual settings/install activity is launched by these tests. */
@RunWith(AndroidJUnit4::class)
class UpdatePermissionGateTest {
    private class CapturingContext(base: Context) : ContextWrapper(base) {
        var launched: Intent? = null
        override fun startActivity(intent: Intent) { launched = intent }
    }

    @Test fun missingInstallPermissionBlocksDownloadAndResume() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        assumeFalse("Test requires the fresh test app to have unknown-source installs disabled", base.packageManager.canRequestPackageInstalls())
        val context = CapturingContext(base)
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor {
            calls++
            throw AssertionError("Permission gate must run before any network request")
        }.build()
        val updater = AppUpdateManager(client, context)
        updater.downloadAndInstall("https://github.com/saabtv-gf/saabtv-core/releases/download/v-test/app.apk", "a".repeat(64))
        assertEquals(UpdateState.AwaitingInstallPermission, updater.state.value)
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, context.launched?.action)
        assertEquals("package:${context.packageName}", context.launched?.dataString)
        updater.resumePendingUpdate()
        assertEquals(0, calls)
        assertEquals(UpdateState.AwaitingInstallPermission, updater.state.value)
        updater.cancelPendingUpdate()
        assertEquals(UpdateState.Idle, updater.state.value)
    }

    @Test fun invalidDownloadNeverOpensPermissionSettings() = runBlocking {
        val context = CapturingContext(ApplicationProvider.getApplicationContext())
        val updater = AppUpdateManager(OkHttpClient(), context)
        updater.downloadAndInstall("https://attacker.test/app.apk", "a".repeat(64))
        assertTrue(updater.state.value is UpdateState.Error)
        assertNull(context.launched)
    }
}
