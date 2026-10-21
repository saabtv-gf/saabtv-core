package com.saab.tv.ui.utils

import android.content.Context
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.memory.MemoryCache
import coil.size.Scale
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * Netflix-grade image prefetcher for smooth scrolling.
 * Prefetches images ahead of the focused item to eliminate loading delays.
 */
object ImagePrefetcher {
    
    private fun artworkSize(context: Context, requestedSize: DpSize) =
        artworkDecodeSizePx(requestedSize.width, requestedSize.height, displayDensity(context))
    private fun logoSize(context: Context) = artworkDecodeSizePx(150.dp, 45.dp, displayDensity(context))
    private fun displayDensity(context: Context) = Density(context.resources.displayMetrics.density)
    
    // Number of items to prefetch ahead
    private const val PREFETCH_COUNT = 2
    private const val PREFETCH_DEDUP_WINDOW_MS = 2_000L
    private const val AROUND_PREFETCH_SKIP_WINDOW_MS = 100L
    private const val AROUND_PREFETCH_REDUCED_WINDOW_MS = 220L
    private const val RECENT_PREFETCH_CAPACITY = 512
    private val recentPrefetches = LinkedHashMap<String, Long>(
        RECENT_PREFETCH_CAPACITY,
        0.75f,
        true
    )
    private var lastAroundPrefetchAtMs = 0L

    /** Warm only the nearest hero backdrops; poster requests do not populate this size's cache. */
    fun prefetchBackdrop(context: Context, url: String?) {
        if (url.isNullOrBlank() || !shouldEnqueue(context, "backdrop:$url")) return
        context.imageLoader.enqueue(
            ImageRequest.Builder(context)
                .data(url)
                .size(1280, 720)
                .memoryCacheKey("backdrop:$url")
                .diskCacheKey(url)
                .scale(Scale.FILL)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .build()
        )
    }

    @Synchronized
    private fun shouldEnqueue(context: Context, key: String): Boolean =
        context.imageLoader.memoryCache?.get(MemoryCache.Key(key)) == null && shouldEnqueue(key)

    @Synchronized
    private fun shouldEnqueue(url: String): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        val lastEnqueuedAt = recentPrefetches[url]
        if (lastEnqueuedAt != null && now - lastEnqueuedAt < PREFETCH_DEDUP_WINDOW_MS) {
            return false
        }

        recentPrefetches[url] = now
        if (recentPrefetches.size > RECENT_PREFETCH_CAPACITY) {
            val oldest = recentPrefetches.entries.iterator()
            if (oldest.hasNext()) {
                oldest.next()
                oldest.remove()
            }
        }
        return true
    }

    @Synchronized
    private fun effectiveAroundCount(requestedCount: Int): Int {
        val now = android.os.SystemClock.uptimeMillis()
        val delta = now - lastAroundPrefetchAtMs
        lastAroundPrefetchAtMs = now

        val safeRequested = requestedCount.coerceAtLeast(1)
        return when {
            delta in 0 until AROUND_PREFETCH_SKIP_WINDOW_MS -> 0
            delta in AROUND_PREFETCH_SKIP_WINDOW_MS until AROUND_PREFETCH_REDUCED_WINDOW_MS ->
                (safeRequested / 2).coerceAtLeast(2)
            else -> safeRequested
        }
    }
    
    /**
     * Prefetch a single image URL
     */
    fun prefetch(context: Context, url: String?, requestedSize: DpSize = DpSize(140.dp, 210.dp)) {
        if (url.isNullOrBlank()) return
        val size = artworkSize(context, requestedSize)
        val cacheKey = artworkMemoryCacheKey(ArtworkCacheRole.Poster.key, url, size)
        if (!shouldEnqueue(context, cacheKey)) return

        context.imageLoader.enqueue(artworkImageRequest(context, url, ArtworkCacheRole.Poster, size))
    }
    
    /**
     * Prefetch multiple images around a focus index
     * Symmetric prefetching: equal in both directions for smooth bi-directional scrolling
     */
    fun prefetchAround(
        context: Context,
        items: List<String?>,
        focusedIndex: Int,
        count: Int = PREFETCH_COUNT
    ) {
        val aroundCount = effectiveAroundCount(count)
        if (aroundCount == 0) return

        // Prefetch ahead (right direction)
        for (i in 1..aroundCount) {
            val aheadIndex = focusedIndex + i
            if (aheadIndex < items.size) {
                prefetch(context, items[aheadIndex])
            }
        }

        // Prefetch behind (left direction) - symmetric for smooth reverse scrolling
        for (i in 1..aroundCount) {
            val behindIndex = focusedIndex - i
            if (behindIndex >= 0) {
                prefetch(context, items[behindIndex])
            }
        }
    }

    /**
     * Prefetch a single landscape-sized image URL
     */
    fun prefetchLandscape(
        context: Context,
        url: String?,
        requestedSize: DpSize = DpSize(190.dp, 190.dp * (9f / 16f))
    ) {
        if (url.isNullOrBlank()) return
        val size = artworkSize(context, requestedSize)
        val cacheKey = artworkMemoryCacheKey(ArtworkCacheRole.Landscape.key, url, size)
        if (!shouldEnqueue(context, cacheKey)) return

        context.imageLoader.enqueue(artworkImageRequest(context, url, ArtworkCacheRole.Landscape, size))
    }

    /** Warm the small transparent title mark separately from the full-card backdrop. */
    fun prefetchLogo(context: Context, url: String?) {
        if (url.isNullOrBlank()) return
        val size = logoSize(context)
        val cacheKey = artworkMemoryCacheKey(ArtworkCacheRole.Logo.key, url, size)
        if (!shouldEnqueue(context, cacheKey)) return
        context.imageLoader.enqueue(artworkImageRequest(context, url, ArtworkCacheRole.Logo, size))
    }

    /**
     * Prefetch landscape images around a focus index
     */
    fun prefetchAroundLandscape(
        context: Context,
        items: List<String?>,
        focusedIndex: Int,
        count: Int = PREFETCH_COUNT,
        logos: List<String?> = emptyList()
    ) {
        val aroundCount = effectiveAroundCount(count)
        if (aroundCount == 0) return

        for (i in 1..aroundCount) {
            val aheadIndex = focusedIndex + i
            if (aheadIndex < items.size) {
                prefetchLandscape(context, items[aheadIndex])
                logos.getOrNull(aheadIndex)?.let { prefetchLogo(context, it) }
            }
        }

        for (i in 1..aroundCount) {
            val behindIndex = focusedIndex - i
            if (behindIndex >= 0) {
                prefetchLandscape(context, items[behindIndex])
                logos.getOrNull(behindIndex)?.let { prefetchLogo(context, it) }
            }
        }
    }
}
