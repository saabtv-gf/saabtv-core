package com.saab.tv.data.cache

import android.app.Application
import android.content.Context
import dev.jdtech.mpv.MPVLib
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class LibMpvThumbnailEngineScenarioTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun openLoadsRemoteStreamAndFreezesTheThumbnailDecoder() = runBlocking {
        val session = FakeSession()
        val engine = engine(session)

        assertTrue(engine.open("https://media.example/movie.mkv?token=secret"))

        assertEquals("https://media.example/movie.mkv?token=secret", session.loadedUrl)
        assertTrue(session.paused)
        assertTrue(engine.initializationTrace.contains("rejected=0"))
        assertTrue(session.options.containsKey("audio"))
        session.observer?.eventProperty("duration")
        session.observer?.eventProperty("duration", 1L)
        session.observer?.eventProperty("duration", 1.0)
        session.observer?.eventProperty("seeking", false)
        session.observer?.eventProperty("path", "fixture")
        engine.close()
    }

    @Test fun openUsesPropertyPollingWhenNativeFileLoadedEventIsMissing() = runBlocking {
        val session = FakeSession(emitLoadedOnLoad = false)
        session.path = "https://media.example/loaded-by-property.mp4"
        session.durationSeconds = 120.0
        val engine = engine(session)

        assertTrue(engine.open(session.path!!))

        assertTrue(session.paused)
        engine.close()
    }

    @Test fun nullFactoryBlankUrlAndClosedEngineFailWithoutStartingNativeWork() = runBlocking {
        val nullEngine = LibMpvThumbnailEngine(context) { null }
        assertFalse(nullEngine.open("https://media.example/movie.mkv"))
        assertTrue(nullEngine.lastError.orEmpty().contains("returned null"))

        val blankSession = FakeSession()
        val blankEngine = engine(blankSession)
        assertFalse(blankEngine.open("  "))
        assertTrue(blankSession.commands.isEmpty())
        blankEngine.close()

        val closedSession = FakeSession()
        val closedEngine = engine(closedSession)
        closedEngine.close()
        assertFalse(closedEngine.open("https://media.example/movie.mkv"))
        assertTrue(closedSession.commands.isEmpty())
    }

    @Test fun nativeInitializationAndLoadFailuresAreReportedAndCloseSafely() = runBlocking {
        val initSession = FakeSession(initError = "decoder init failed")
        val initEngine = engine(initSession)
        assertFalse(initEngine.open("https://media.example/movie.mkv"))
        assertTrue(initEngine.lastError.orEmpty().contains("mpv_initialize decoder init failed"))
        assertTrue(initSession.destroyed)

        val loadSession = FakeSession(loadResult = -7, nativeLogs = "load failed")
        val loadEngine = engine(loadSession)
        assertFalse(loadEngine.open("https://media.example/movie.mkv"))
        assertTrue(loadEngine.lastError.orEmpty().contains("command=loadfile code=-7"))
        loadEngine.close()

        val thrownSession = FakeSession(throwOnInit = true)
        val thrownEngine = engine(thrownSession)
        assertFalse(thrownEngine.open("https://media.example/movie.mkv"))
        assertTrue(thrownEngine.lastError.orEmpty().contains("IllegalStateException: fixture init exception"))
        assertTrue(thrownSession.destroyed)
    }

    @Test fun streamEndedBeforeLoadAndRejectedNativeOptionHaveUsefulDiagnostics() = runBlocking {
        val endedSession = FakeSession(endOnLoad = true, nativeLogs = "stream closed")
        val endedEngine = engine(endedSession)
        assertFalse(endedEngine.open("https://media.example/movie.mkv"))
        assertTrue(endedEngine.lastError.orEmpty().contains("stream ended while opening"))
        endedEngine.close()

        val rejectedSession = FakeSession(optionFailures = mapOf("vo" to -1))
        val rejectedEngine = engine(rejectedSession)
        assertTrue(rejectedEngine.open("https://media.example/movie.mkv"))
        assertTrue(rejectedEngine.initializationTrace.contains("rejected=1"))
        assertTrue(rejectedEngine.initializationTrace.contains("vo=-1"))
        rejectedEngine.close()
    }

    @Test fun closeCancelsOpenWaitAndIsIdempotent() = runBlocking {
        val session = FakeSession(emitLoadedOnLoad = false, propertyPathAvailable = false)
        val engine = engine(session)
        val opening = async { engine.open("https://media.example/slow.mkv") }
        delay(40)
        engine.close()

        assertFalse(opening.await())
        engine.close()
        assertEquals(1, session.destroyCount)
        assertEquals(1, session.stopCount)
    }

    @Test fun captureBeforeOpenAndAfterCloseDoesNotTouchDecoder() = runBlocking {
        val session = FakeSession()
        val engine = engine(session)
        assertNull(engine.capture(10_000))
        assertTrue(engine.open("https://media.example/movie.mkv"))
        engine.close()
        assertNull(engine.capture(10_000))
    }

    @Test fun exactSeekProducesTimestampedThumbnailAndUsesLocaleIndependentSeconds() = runBlocking {
        val session = FakeSession(frame = rawFrame(1, 1))
        val engine = engine(session)
        assertTrue(engine.open("https://media.example/movie.mkv"))

        val bitmap = engine.capture(12_345)

        assertNotNull(bitmap)
        assertEquals(12_345L, engine.lastCapturePositionMs)
        assertEquals("12.345", session.seekArgument)
        assertTrue(session.commands.any { it.firstOrNull() == "seek" && it.last() == "absolute+exact" })
        bitmap?.recycle()
        engine.close()
    }

    @Test fun seekFailureReturnsDiagnosticsAndDoesNotAttemptFrameCapture() = runBlocking {
        val session = FakeSession(seekResult = -4, nativeLogs = "seek refused")
        val engine = engine(session)
        assertTrue(engine.open("https://media.example/movie.mkv"))

        assertNull(engine.capture(5_000))

        assertTrue(engine.lastError.orEmpty().contains("command=seek code=-4"))
        assertEquals(0, session.screenshotCalls)
        engine.close()
    }

    @Test fun endOfFileDuringSeekOrFrameStepStopsWaitingImmediately() = runBlocking {
        val seekEnded = FakeSession(endDuringSeek = true)
        val seekEngine = engine(seekEnded)
        assertTrue(seekEngine.open("https://media.example/movie.mkv"))
        assertNull(seekEngine.capture(2_000))
        assertTrue(seekEngine.lastError.orEmpty().contains("stream ended during seek"))
        assertFalse(seekEnded.commands.any { it.firstOrNull() == "frame-step" })
        seekEngine.close()

        val waitEnded = FakeSession(endOnScreenshotCall = 2)
        val waitEngine = engine(waitEnded)
        assertTrue(waitEngine.open("https://media.example/movie.mkv"))
        assertNull(waitEngine.capture(2_000))
        assertTrue(waitEngine.lastError.orEmpty().contains("stream ended during seek"))
        assertEquals(2, waitEnded.screenshotCalls)
        waitEngine.close()
    }

    @Test fun frameStepFallbackCanRecoverAFrameWhenInitialScreenshotIsEmpty() = runBlocking {
        val session = FakeSession(frame = rawFrame(2, 1), emptyScreenshotCount = 1)
        val engine = engine(session)
        assertTrue(engine.open("https://media.example/movie.mkv"))

        val bitmap = engine.capture(2_000)

        assertNotNull(bitmap)
        assertTrue(session.commands.any { it.firstOrNull() == "frame-step" })
        assertEquals(2, session.screenshotCalls)
        bitmap?.recycle()
        engine.close()
    }

    @Test fun frameStepFailureAndInvalidRawFrameReturnNoThumbnail() = runBlocking {
        val stepFailureSession = FakeSession(frameStepResult = -3, emptyScreenshotCount = 1)
        val stepFailureEngine = engine(stepFailureSession)
        assertTrue(stepFailureEngine.open("https://media.example/movie.mkv"))
        assertNull(stepFailureEngine.capture(1_000))
        assertTrue(stepFailureEngine.lastError.orEmpty().contains("command=frame-step code=-3"))
        stepFailureEngine.close()

        val invalidFrameSession = FakeSession(frame = byteArrayOf(1, 2, 3))
        val invalidFrameEngine = engine(invalidFrameSession)
        assertTrue(invalidFrameEngine.open("https://media.example/movie.mkv"))
        assertNull(invalidFrameEngine.capture(1_000))
        assertTrue(invalidFrameEngine.lastError.orEmpty().contains("invalid screenshot-raw payload"))
        invalidFrameEngine.close()
    }

    @Test fun seekMustSettleOnTheRequestedFrameAndTransportUrlsAreSanitized() = runBlocking {
        val session = FakeSession(
            timeOffsetSeconds = 60.0,
            emitRestartOnSeek = false,
            nativeLogs = "failed https://media.example/movie.mkv?token=secret"
        )
        val engine = engine(session)
        assertTrue(engine.open("https://media.example/movie.mkv"))
        val capture = async { engine.capture(3_000) }
        delay(40)
        engine.close()

        assertNull(capture.await())
        assertTrue(engine.lastError.orEmpty().contains("exact seek did not settle"))
        assertFalse(engine.lastError.orEmpty().contains("token=secret"))
    }

    @Test fun captureRejectsTimestampMismatchAndContainsNativeFrameExceptions() = runBlocking {
        val mismatchSession = FakeSession(frame = rawFrame(1, 1), positionReadOverrides = mutableListOf(3.0, 9.0))
        val mismatchEngine = engine(mismatchSession)
        assertTrue(mismatchEngine.open("https://media.example/movie.mkv"))
        assertNull(mismatchEngine.capture(3_000))
        assertTrue(mismatchEngine.lastError.orEmpty().contains("frame timestamp mismatch"))
        mismatchEngine.close()

        val exceptionSession = FakeSession(throwOnScreenshot = true)
        val exceptionEngine = engine(exceptionSession)
        assertTrue(exceptionEngine.open("https://media.example/movie.mkv"))
        assertNull(exceptionEngine.capture(3_000))
        assertTrue(exceptionEngine.lastError.orEmpty().contains("IllegalStateException: fixture screenshot exception"))
        exceptionEngine.close()
    }

    @Test fun closeAttemptsEveryNativeCleanupEvenIfEachCallThrows() {
        val session = FakeSession(throwOnRemove = true, throwOnStop = true, throwOnDestroy = true)
        val engine = engine(session)
        assertTrue(runBlocking { engine.open("https://media.example/movie.mkv") })

        engine.close()

        assertEquals(1, session.removeCount)
        assertEquals(1, session.stopCount)
        assertEquals(1, session.destroyCount)
    }

    @Test fun cancellationIsPropagatedInsteadOfConvertedIntoAFrameFailure() = runBlocking {
        val session = FakeSession(emitRestartOnSeek = false)
        val engine = engine(session)
        assertTrue(engine.open("https://media.example/movie.mkv"))
        val capture = async { engine.capture(3_000) }
        delay(40)
        capture.cancelAndJoin()
        assertTrue(capture.isCancelled)
        engine.close()
        assertTrue(session.destroyed)
    }

    private fun engine(session: FakeSession) = LibMpvThumbnailEngine(context) { session }

    private fun rawFrame(width: Int, height: Int): ByteArray {
        val pixels = ByteArray(width * height * 4) { 0x7f }
        return ByteBuffer.allocate(12 + pixels.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(width).putInt(height).putInt(width * 4).put(pixels).array()
    }

    private class FakeSession(
        var initError: String? = null,
        var loadResult: Int = 0,
        var seekResult: Int = 0,
        var frameStepResult: Int = 0,
        var frame: ByteArray? = null,
        var emitLoadedOnLoad: Boolean = true,
        var endOnLoad: Boolean = false,
        var endDuringSeek: Boolean = false,
        var endOnScreenshotCall: Int? = null,
        var emitRestartOnSeek: Boolean = true,
        var timeOffsetSeconds: Double = 0.0,
        var propertyPathAvailable: Boolean = true,
        var emptyScreenshotCount: Int = 0,
        var positionReadOverrides: MutableList<Double> = mutableListOf(),
        var throwOnInit: Boolean = false,
        var throwOnScreenshot: Boolean = false,
        var throwOnRemove: Boolean = false,
        var throwOnStop: Boolean = false,
        var throwOnDestroy: Boolean = false,
        var nativeLogs: String = "",
        val optionFailures: Map<String, Int> = emptyMap()
    ) : LibMpvSession {
        val options = mutableMapOf<String, String>()
        val commands = mutableListOf<List<String>>()
        var observer: MPVLib.EventObserver? = null
        var path: String? = null
        var durationSeconds = 1_800.0
        var positionSeconds = 0.0
        var loadedUrl: String? = null
        var seekArgument: String? = null
        var paused = false
        var screenshotCalls = 0
        var removeCount = 0
        var destroyCount = 0
        var stopCount = 0
        val destroyed get() = destroyCount > 0

        override fun addObserver(observer: MPVLib.EventObserver) { this.observer = observer }
        override fun removeObserver(observer: MPVLib.EventObserver) {
            removeCount++
            if (throwOnRemove) error("fixture remove exception")
            if (this.observer === observer) this.observer = null
        }
        override fun setOptionString(name: String, value: String): Int {
            options[name] = value
            return optionFailures[name] ?: 0
        }
        override fun initDetailed(): String? {
            if (throwOnInit) error("fixture init exception")
            return initError
        }
        override fun commandDetailed(args: Array<String>): Int {
            commands += args.toList()
            when (args.firstOrNull()) {
                "loadfile" -> {
                    loadedUrl = args.getOrNull(1)
                    path = loadedUrl
                    if (endOnLoad) observer?.event(MPVLib.MpvEvent.MPV_EVENT_END_FILE)
                    else if (emitLoadedOnLoad) observer?.event(MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED)
                    return loadResult
                }
                "seek" -> {
                    seekArgument = args.getOrNull(1)
                    positionSeconds = (seekArgument?.toDoubleOrNull() ?: 0.0) + timeOffsetSeconds
                    if (endDuringSeek) observer?.event(MPVLib.MpvEvent.MPV_EVENT_END_FILE)
                    if (emitRestartOnSeek) observer?.event(MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART)
                    return seekResult
                }
                "frame-step" -> return frameStepResult
            }
            return 0
        }
        override fun command(args: Array<String>) {
            commands += args.toList()
            if (args.firstOrNull() == "stop") {
                stopCount++
                if (throwOnStop) error("fixture stop exception")
            }
        }
        override fun setPropertyBoolean(name: String, value: Boolean) { if (name == "pause") paused = value }
        override fun getPropertyString(name: String): String? = if (name == "path" && propertyPathAvailable) path else null
        override fun getPropertyDouble(name: String): Double? = when (name) {
            "duration" -> durationSeconds
            "time-pos" -> if (positionReadOverrides.isNotEmpty()) positionReadOverrides.removeAt(0) else positionSeconds
            else -> null
        }
        override fun getPropertyBoolean(name: String): Boolean? = if (name == "seeking") false else null
        override fun drainDiagnosticLogs(): String = nativeLogs
        override fun screenshotRaw(): ByteArray? {
            screenshotCalls++
            if (throwOnScreenshot) error("fixture screenshot exception")
            if (endOnScreenshotCall == screenshotCalls) observer?.event(MPVLib.MpvEvent.MPV_EVENT_END_FILE)
            if (emptyScreenshotCount > 0) {
                emptyScreenshotCount--
                return null
            }
            return frame
        }
        override fun screenshotRawError(): String = "fixture has no frame"
        override fun destroy() {
            destroyCount++
            if (throwOnDestroy) error("fixture destroy exception")
        }
    }
}
