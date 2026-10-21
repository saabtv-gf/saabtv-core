package com.saab.tv.ui.utils

import android.app.Application
import androidx.compose.ui.unit.IntSize
import coil.size.Scale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ArtworkImageRequestTest {
    @Test
    fun visibleAndPrefetchedArtworkShareDecodeCacheAndScaleContract() {
        val context = RuntimeEnvironment.getApplication()
        val size = IntSize(475, 267)

        val poster = artworkImageRequest(context, "poster-url", ArtworkCacheRole.Poster, size)
        val landscape = artworkImageRequest(context, "backdrop-url", ArtworkCacheRole.Landscape, size)
        val logo = artworkImageRequest(context, "logo-url", ArtworkCacheRole.Logo, size)
        val hub = artworkImageRequest(context, "hub-url", ArtworkCacheRole.Hub, size)

        assertEquals("poster:poster-url:475x267", poster.memoryCacheKey?.key)
        assertEquals("backdrop-url", landscape.diskCacheKey)
        assertEquals("landscape:backdrop-url:475x267", landscape.memoryCacheKey?.key)
        assertEquals("logo:logo-url:475x267", logo.memoryCacheKey?.key)
        assertEquals("hub:hub-url:475x267", hub.memoryCacheKey?.key)
        assertEquals(Scale.FILL, poster.scale)
        assertEquals(Scale.FILL, landscape.scale)
        assertEquals(Scale.FIT, logo.scale)
        assertEquals(Scale.FILL, hub.scale)
    }
}
