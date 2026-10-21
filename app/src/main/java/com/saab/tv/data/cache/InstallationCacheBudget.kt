package com.saab.tv.data.cache

import android.app.ActivityManager
import android.content.Context
import java.io.File

/** Initial device-tier caps for regenerable caches; tune against real Fire TV measurements. */
internal data class InstallationCacheBudget(
    val artworkMemoryBytes: Long,
    val artworkDiskBytes: Long,
    val metadataHttpBytes: Long,
    val seekThumbnailBytes: Long
) {
    val managedDiskBytes: Long
        get() = artworkDiskBytes + metadataHttpBytes + seekThumbnailBytes

    companion object {
        private const val MIB = 1024L * 1024L

        fun forDevice(memoryClassMiB: Int, isLowRamDevice: Boolean): InstallationCacheBudget = when {
            isLowRamDevice || memoryClassMiB <= 128 -> InstallationCacheBudget(
                artworkMemoryBytes = 20 * MIB,
                artworkDiskBytes = 60 * MIB,
                metadataHttpBytes = 15 * MIB,
                seekThumbnailBytes = 150 * MIB
            )
            memoryClassMiB <= 256 -> InstallationCacheBudget(
                artworkMemoryBytes = 32 * MIB,
                artworkDiskBytes = 80 * MIB,
                metadataHttpBytes = 20 * MIB,
                seekThumbnailBytes = 200 * MIB
            )
            else -> InstallationCacheBudget(
                artworkMemoryBytes = 48 * MIB,
                artworkDiskBytes = 100 * MIB,
                metadataHttpBytes = 30 * MIB,
                seekThumbnailBytes = 250 * MIB
            )
        }

        fun forContext(context: Context): InstallationCacheBudget {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            return forDevice(
                memoryClassMiB = manager?.memoryClass ?: 128,
                isLowRamDevice = manager?.isLowRamDevice ?: true
            )
        }

        /** Evicts only regenerable inactive-account cache directories before opening this scope. */
        fun pruneInactiveScopes(cacheRoot: File, activeScope: String, maxBytes: Long) {
            if (maxBytes <= 0L || !cacheRoot.isDirectory) return
            val directories = cacheRoot.listFiles()?.filter { it.isDirectory }.orEmpty()
            val inactive = directories.filter { it.name != activeScope }
            // A normal single-account startup has nothing to evict. Avoid walking
            // thousands of active cache files during singleton initialization.
            if (inactive.isEmpty()) return
            var totalBytes = directories.sumOf(::directoryBytes)
            for (directory in inactive.asSequence()
                .sortedBy { it.lastModified() }) {
                if (totalBytes <= maxBytes) break
                val bytes = directoryBytes(directory)
                if (directory.deleteRecursively()) totalBytes -= bytes
            }
        }

        private fun directoryBytes(directory: File): Long =
            directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}
