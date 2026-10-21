package com.saab.tv.ui.utils

import android.app.Application
import android.graphics.Bitmap
import coil.Coil
import coil.ImageLoader
import coil.memory.MemoryCache
import coil.request.ImageRequest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import java.lang.reflect.Proxy
import java.util.UUID
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ImagePrefetcherPerformanceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var cache: MemoryCache
    private val requests = mutableListOf<ImageRequest>()

    private fun expectedMemoryKeys(url: String): Set<MemoryCache.Key> {
        val density = Density(context.resources.displayMetrics.density)
        return setOf(
            MemoryCache.Key(artworkMemoryCacheKey("poster", url, artworkDecodeSizePx(140.dp, 210.dp, density))),
            MemoryCache.Key(artworkMemoryCacheKey("landscape", url, artworkDecodeSizePx(190.dp, 190.dp * (9f / 16f), density))),
            MemoryCache.Key(artworkMemoryCacheKey("logo", url, artworkDecodeSizePx(150.dp, 45.dp, density))),
            MemoryCache.Key("backdrop:$url")
        )
    }

    @Before fun setUp() {
        cache = MemoryCache.Builder(context).maxSizeBytes(1_048_576).build()
        val loader = Proxy.newProxyInstance(ImageLoader::class.java.classLoader,
            arrayOf(ImageLoader::class.java)) { _, method, args ->
            when (method.name) {
                "getMemoryCache" -> cache
                "enqueue" -> { requests += args!![0] as ImageRequest; null }
                else -> null
            }
        } as ImageLoader
        Coil.setImageLoader(loader)
    }
    @After fun tearDown() { Coil.reset(); cache.clear() }

    @Test fun cachedArtworkDoesNotQueueWorkOnFocus() {
        val url = "https://image.test/${UUID.randomUUID()}"
        for (key in expectedMemoryKeys(url)) {
            cache[key] = MemoryCache.Value(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888))
        }
        ImagePrefetcher.prefetch(context, url)
        ImagePrefetcher.prefetchLandscape(context, url)
        ImagePrefetcher.prefetchLogo(context, url)
        ImagePrefetcher.prefetchBackdrop(context, url)
        assertTrue(requests.isEmpty())
    }

    @Test fun sizesUseSeparateMemoryEntriesButReuseOneDiskDownload() {
        val url = "https://image.test/${UUID.randomUUID()}"
        repeat(3) {
            ImagePrefetcher.prefetch(context, url)
            ImagePrefetcher.prefetchLandscape(context, url)
            ImagePrefetcher.prefetchLogo(context, url)
            ImagePrefetcher.prefetchBackdrop(context, url)
        }
        assertEquals(4, requests.size)
        assertEquals(setOf(url), requests.map { it.diskCacheKey }.toSet())
        assertEquals(4, requests.map { it.memoryCacheKey }.distinct().size)
        assertEquals(expectedMemoryKeys(url), requests.mapNotNull { it.memoryCacheKey }.toSet())
    }

    @Test fun customPosterSizeReusesTheVisibleCardsMemoryEntry() {
        val url = "https://image.test/${UUID.randomUUID()}"
        val size = DpSize(80.dp, 120.dp)
        val key = artworkMemoryCacheKey("poster", url, artworkDecodeSizePx(size.width, size.height,
            Density(context.resources.displayMetrics.density)))
        cache[MemoryCache.Key(key)] = MemoryCache.Value(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888))

        ImagePrefetcher.prefetch(context, url, size)

        assertTrue(requests.isEmpty())
    }

    @Test fun customLandscapeSizeReusesTheVisibleCardsMemoryEntry() {
        val url = "https://image.test/${UUID.randomUUID()}"
        val size = DpSize(320.dp, 180.dp)
        val key = artworkMemoryCacheKey("landscape", url, artworkDecodeSizePx(size.width, size.height,
            Density(context.resources.displayMetrics.density)))
        cache[MemoryCache.Key(key)] = MemoryCache.Value(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888))

        ImagePrefetcher.prefetchLandscape(context, url, size)

        assertTrue(requests.isEmpty())
    }
}
