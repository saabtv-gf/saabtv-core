package com.saab.tv.data.update

import android.app.Application
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/** Intercept HTTP before the socket: exercise the service, not just release policies. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class UpdaterServiceRegressionTest {
    private fun manager(json: String, code: Int = 200, requests: AtomicInteger = AtomicInteger()): AppUpdateManager {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
                .body(json.toResponseBody("application/json".toMediaType())).build()
        }.build()
        return AppUpdateManager(client, RuntimeEnvironment.getApplication())
    }
    private fun release(version: String = "v99.0.0", url: String = "https://github.com/saabtv-gf/saabtv-core/releases/download/v99/app.apk",
                        digest: String = "sha256:${"a".repeat(64)}") =
        """{"tag_name":"$version","body":"Release Notes","assets":[{"name":"app.apk","browser_download_url":"$url","digest":"$digest"}]}"""
    @Test fun availableUpdateContainsValidatedUrlChecksumAndNotes() = runBlocking {
        val vm = manager(release()); vm.checkForUpdate()
        val state = vm.state.value as UpdateState.UpdateAvailable
        assertEquals("99.0.0", state.info.versionName); assertEquals("a".repeat(64), state.info.sha256)
        assertEquals("Release Notes", state.info.changelog)
    }
    @Test fun olderReleaseIsUpToDateRatherThanDowngrade() = runBlocking {
        val vm = manager(release("v0.0.1")); vm.checkForUpdate()
        assertEquals(UpdateState.UpToDate, vm.state.value)
    }
    @Test fun untrustedReleaseAssetIsRejectedBeforeDownload() = runBlocking {
        val vm = manager(release(url = "https://evil.invalid/app.apk")); vm.checkForUpdate()
        assertTrue(vm.state.value is UpdateState.Error)
        assertTrue((vm.state.value as UpdateState.Error).message.contains("trusted"))
    }
    @Test fun missingChecksumAndMissingCompatibleApkAreActionableErrors() = runBlocking {
        val noHash = manager(release(digest = "")); noHash.checkForUpdate()
        assertTrue((noHash.state.value as UpdateState.Error).message.contains("checksum"))
        val noApk = manager("""{"tag_name":"v99.0.0","assets":[],"body":"Notes"}"""); noApk.checkForUpdate()
        assertTrue((noApk.state.value as UpdateState.Error).message.contains("compatible"))
    }
    @Test fun malformedResponseBecomesErrorAndCanBeRetriedAfterReset() = runBlocking {
        val vm = manager("not json"); vm.checkForUpdate()
        assertTrue(vm.state.value is UpdateState.Error)
        vm.resetState(); assertEquals(UpdateState.Idle, vm.state.value)
        vm.checkForUpdate(); assertTrue(vm.state.value is UpdateState.Error)
    }
    @Test fun httpFailureIsContainedWithoutUncaughtException() = runBlocking {
        val vm = manager("unavailable", 503); vm.checkForUpdate()
        assertTrue(vm.state.value is UpdateState.Error)
    }
    @Test fun invalidDownloadUrlOrHashNeverStartsNetworkTransfer() = runBlocking {
        val requests = AtomicInteger(); val vm = manager(release(), requests = requests)
        vm.downloadAndInstall("https://evil.invalid/app.apk", "a".repeat(64))
        assertTrue(vm.state.value is UpdateState.Error); assertEquals(0, requests.get())
        vm.downloadAndInstall("https://github.com/saabtv-gf/saabtv-core/releases/download/v99/app.apk", "invalid")
        assertTrue(vm.state.value is UpdateState.Error); assertEquals(0, requests.get())
    }
    @Test fun cancelAndResumeWithoutPendingUpdateDoNotDownload() = runBlocking {
        val requests = AtomicInteger(); val vm = manager(release(), requests = requests)
        vm.cancelPendingUpdate(); vm.resumePendingUpdate()
        assertEquals(0, requests.get()); assertEquals(UpdateState.Idle, vm.state.value)
    }
    @Test fun permissionDenialAndRepeatedResumeNeverTransferApk() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        org.robolectric.Shadows.shadowOf(context.packageManager).setCanRequestPackageInstalls(false)
        val requests = AtomicInteger(); val vm = manager(release(), requests = requests)
        vm.downloadAndInstall("https://github.com/saabtv-gf/saabtv-core/releases/download/v99/app.apk", "a".repeat(64))
        assertEquals(UpdateState.AwaitingInstallPermission, vm.state.value)
        assertEquals(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            org.robolectric.Shadows.shadowOf(context).nextStartedActivity.action)
        repeat(3) { vm.resumePendingUpdate() }
        assertEquals(0,requests.get()); vm.cancelPendingUpdate(); vm.resumePendingUpdate()
        assertEquals(UpdateState.Idle,vm.state.value); assertEquals(0,requests.get())
    }
}
