package com.saab.tv.di

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.saab.tv.data.cache.InstallationCacheBudget
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ImageLoaderModule {
    
    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context,
        okHttpClient: OkHttpClient
    ): ImageLoader {
        val budget = InstallationCacheBudget.forContext(context)
        val scope = com.saab.tv.data.account.AccountStorage.scope(context)
        val cacheRoot = context.cacheDir.resolve("image_cache")
        InstallationCacheBudget.pruneInactiveScopes(cacheRoot, scope, budget.artworkDiskBytes)
        return ImageLoader.Builder(context)
            // Use shared OkHttpClient with connection pooling
            .okHttpClient(okHttpClient)
            // Keep poster reuse fast without competing with the video decoder,
            // ExoPlayer's sample queue, and the thumbnail worker on TV hardware.
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizeBytes(budget.artworkMemoryBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                    .strongReferencesEnabled(true)
                    .build()
            }
            // Disk cache for offline/fast reload
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheRoot.resolve(scope))
                    .maxSizeBytes(budget.artworkDiskBytes)
                    .build()
            }
            // Enable hardware bitmaps for GPU-accelerated rendering on TV
            .allowHardware(true)
            // Respect cache headers less aggressively for media content
            .respectCacheHeaders(false)
            // Crossfade disabled globally - we control it per-request based on scroll state
            .crossfade(false)
            // Dispatch immediately to reduce latency for first images
            .build()
    }
}
