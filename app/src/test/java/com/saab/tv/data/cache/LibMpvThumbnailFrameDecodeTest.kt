package com.saab.tv.data.cache

import android.app.Application
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class LibMpvThumbnailFrameDecodeTest {
    private val engine get() = LibMpvThumbnailEngine(RuntimeEnvironment.getApplication())

    @Test fun rejectsTruncatedHeader() {
        assertNull(engine.decodeThumbnail(ByteArray(11)))
    }

    @Test fun rejectsNonPositiveDimensionsAndMismatchedStride() {
        assertNull(engine.decodeThumbnail(frame(0, 1, 0, ByteArray(0))))
        assertNull(engine.decodeThumbnail(frame(1, 1, 3, ByteArray(4))))
    }

    @Test fun rejectsPayloadWithMissingOrExtraPixelBytes() {
        assertNull(engine.decodeThumbnail(frame(1, 1, 4, ByteArray(3))))
        assertNull(engine.decodeThumbnail(frame(1, 1, 4, ByteArray(5))))
    }

    @Test fun rejectsDimensionsWhosePixelBufferCannotFitInByteArray() {
        assertNull(engine.decodeThumbnail(frame(100_000, 100_000, 400_000, ByteArray(0))))
    }

    @Test fun acceptsNativeThumbnailDimensionsWithoutRescaling() {
        val bitmap = engine.decodeThumbnail(frame(320, 180, 1_280, ByteArray(320 * 180 * 4)))

        assertNotNull(bitmap)
        assertEquals(320, bitmap!!.width)
        assertEquals(180, bitmap.height)
        bitmap.recycle()
    }

    @Test fun scalesValidRawFrameToThumbnailDimensions() {
        val bitmap = engine.decodeThumbnail(frame(2, 1, 8, ByteArray(8)))

        assertNotNull(bitmap)
        assertEquals(320, bitmap!!.width)
        assertEquals(160, bitmap.height)
        bitmap.recycle()
    }

    private fun frame(width: Int, height: Int, stride: Int, pixels: ByteArray): ByteArray =
        ByteBuffer.allocate(12 + pixels.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(width).putInt(height).putInt(stride).put(pixels).array()
}
