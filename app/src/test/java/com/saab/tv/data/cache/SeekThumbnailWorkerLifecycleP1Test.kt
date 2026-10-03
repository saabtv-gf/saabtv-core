package com.saab.tv.data.cache

import android.app.Application
import android.content.Intent
import com.saab.tv.data.account.AccountStorage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SeekThumbnailWorkerLifecycleP1Test {
    private val context get() = RuntimeEnvironment.getApplication()
    private val profileId = 918_273
    private lateinit var service: SeekThumbnailWorkerService
    private lateinit var controller: org.robolectric.android.controller.ServiceController<SeekThumbnailWorkerService>

    @Before fun createService() {
        SeekThumbnailWorkerService.setProfileEnabled(context, profileId, true)
        controller = Robolectric.buildService(SeekThumbnailWorkerService::class.java).create()
        service = controller.get()
    }

    @After fun destroyService() {
        runCatching { controller.destroy() }
        SeekThumbnailWorkerService.setProfileEnabled(context, profileId, true)
        AccountStorage.preferences(context, "seek_thumbnail_worker")
            .edit().remove("retry_after_${SeekThumbnailWorkerRequest(profileId, "movie", "https://media.example/video.mp4", 60_000, 10).key.hashCode()}").apply()
    }

    @Test fun disabledProfileRejectsWorkerRequestBeforeAnyDecoderCanStart() {
        SeekThumbnailWorkerService.setProfileEnabled(context, profileId, false)
        service.onStartCommand(validRemoteRequest(), 0, 1)

        assertNull(privateField("activeRequestKey"))
        assertEquals(0L, privateField("generationId"))
        assertNull(privateField("generationJob"))
    }

    @Test fun localFileUrlIsRejectedBeforeWorkerDecoderStarts() {
        service.onStartCommand(validRemoteRequest("file:///storage/video.mkv"), 0, 1)

        assertNull(privateField("activeRequestKey"))
        assertEquals(0L, privateField("generationId"))
        assertNull(privateField("generationJob"))
    }

    @Test fun malformedRequestAndUnknownActionAreNoOps() {
        service.onStartCommand(Intent(), 0, 1)
        service.onStartCommand(Intent().setAction("com.saab.tv.thumbnail.UNKNOWN"), 0, 2)

        assertNull(privateField("activeRequestKey"))
        assertEquals(0L, privateField("generationId"))
    }

    @Test fun cancelForDifferentRequestDoesNotCancelTheActiveBatch() {
        // Seed the internal key without starting native work; then exercise the real
        // service command dispatcher with a non-matching cancellation capability.
        val active = "expected-request-key"
        setPrivateField("activeRequestKey", active)
        val cancel = Intent().setAction("com.saab.tv.thumbnail.CANCEL")
            .putExtra("request_key", "different-request-key")
        service.onStartCommand(cancel, 0, 1)

        assertEquals(active, privateField("activeRequestKey"))
        assertEquals(0L, privateField("generationId"))
    }

    private fun validRemoteRequest(url: String = "https://media.example/video.mp4") = Intent()
        .setAction("com.saab.tv.thumbnail.START")
        .putExtra("profile_id", profileId)
        .putExtra("content_id", "movie")
        .putExtra("media_url", url)
        .putExtra("duration_ms", 60_000L)
        .putExtra("interval_seconds", 10)

    private fun privateField(name: String): Any? = SeekThumbnailWorkerService::class.java
        .getDeclaredField(name).apply { isAccessible = true }.get(service)

    private fun setPrivateField(name: String, value: Any?) {
        SeekThumbnailWorkerService::class.java.getDeclaredField(name).apply { isAccessible = true }
            .set(service, value)
    }
}
