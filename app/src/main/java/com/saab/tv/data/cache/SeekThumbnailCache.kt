package com.saab.tv.data.cache

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class SeekThumbnailProgress(
    val cachedFrames: Int,
    val totalFrames: Int,
    val percent: Int
)

enum class ThumbnailStoreResult { STORED, DUPLICATE, INVALID, CAPACITY_EXCEEDED, WRITE_FAILED }

data class ThumbnailWorkingSetStatus(
    val admitted: Boolean,
    val currentBytes: Long,
    val limitBytes: Long,
    val refusalReason: String? = null
)

@Singleton
class SeekThumbnailCache private constructor(
    @ApplicationContext private val context: Context,
    limits: CacheLimits
) {
    @Inject constructor(@ApplicationContext context: Context) : this(context, deviceLimits(context))

    internal constructor(context: Context, maxCacheBytes: Long, workingSetBytes: Long) : this(
        context,
        CacheLimits(maxCacheBytes, workingSetBytes)
    )

    private val installationRoot = File(context.cacheDir, CACHE_DIRECTORY)
    /** Keep account data separate while enforcing one device-wide evictable byte limit. */
    private val root = File(installationRoot, com.saab.tv.data.account.AccountStorage.scope(context))
    private val maxCacheBytes = limits.cacheBytes
    private val maxWorkingSetBytes = limits.workingSetBytes
    private val ioDispatcher = Executors.newSingleThreadExecutor { task ->
        Thread(task, "seek-thumbnail-cache").apply { priority = Thread.MIN_PRIORITY }
    }.asCoroutineDispatcher()
    private val thumbnailIndexByDirectory = mutableMapOf<String, MutableMap<Long, File>>()
    private val lastDirectoryScanElapsedMs = mutableMapOf<String, Long>()
    /** OS file locks make active timelines visible to the isolated worker and app processes. */
    private val pinnedWorkingSets = mutableMapOf<String, WorkingSetLease>()
    private var localWriterMode = false
    private var writesSinceTrim = 0

    init {
        // Older versions may contain PlayerView captures or libmpv BGRA frames
        // interpreted as RGBA. Never expose stale control overlays or blue-tinted
        // thumbnails after upgrading the pixel pipeline.
        CoroutineScope(ioDispatcher).launch {
            OBSOLETE_CACHE_DIRECTORIES.forEach { directory ->
                runCatching { File(context.cacheDir, directory).deleteRecursively() }
            }
            // Enforce the new installation-wide cap on caches created by older app versions
            // immediately after migration, not only after the next batch of thumbnail writes.
            trimToSize()
        }
    }

    suspend fun store(
        profileId: Int,
        contentId: String,
        positionMs: Long,
        intervalSeconds: Int,
        bitmap: Bitmap
    ): ThumbnailStoreResult = withContext(ioDispatcher) {
        try {
            if (profileId <= 0 || contentId.isBlank() || bitmap.width <= 0 || bitmap.height <= 0) {
                return@withContext ThumbnailStoreResult.INVALID
            }
            val safeInterval = normalizeInterval(intervalSeconds)
            val timestampSeconds = quantizePositionSeconds(positionMs, safeInterval)
            val directory = contentDirectory(profileId, contentId)
            if (!directory.exists() && !directory.mkdirs()) return@withContext ThumbnailStoreResult.WRITE_FAILED
            val destination = File(directory, "$timestampSeconds.webp")
            if (destination.exists()) {
                destination.setLastModified(System.currentTimeMillis())
                thumbnailIndexByDirectory[directory.absolutePath]?.set(timestampSeconds, destination)
                return@withContext ThumbnailStoreResult.DUPLICATE
            }

            val temporary = File(
                directory,
                "$timestampSeconds-${android.os.Process.myPid()}-${System.nanoTime()}.tmp"
            )
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            val written = runCatching {
                FileOutputStream(temporary).use { output ->
                    bitmap.compress(format, WEBP_QUALITY, output)
                }
            }.getOrDefault(false)
            if (!written) {
                temporary.delete()
                return@withContext ThumbnailStoreResult.WRITE_FAILED
            }
            val commitResult = runCatching {
                withWorkingSetRegistryLock {
                    if (destination.exists()) {
                        destination.setLastModified(System.currentTimeMillis())
                        thumbnailIndexByDirectory[directory.absolutePath]?.set(timestampSeconds, destination)
                        temporary.delete()
                        ThumbnailStoreResult.DUPLICATE
                    } else if (
                        isDirectoryPinnedLocked(directory) &&
                        directoryBytes(directory) + temporary.length() > maxWorkingSetBytes
                    ) {
                        temporary.delete()
                        ThumbnailStoreResult.CAPACITY_EXCEEDED
                    } else if (!temporary.renameTo(destination)) {
                        temporary.delete()
                        ThumbnailStoreResult.WRITE_FAILED
                    } else {
                        ThumbnailStoreResult.STORED
                    }
                }
            }.getOrElse {
                // A failed/cancelled lock acquisition must not strand an unindexed frame.
                temporary.delete()
                ThumbnailStoreResult.WRITE_FAILED
            }
            if (commitResult != ThumbnailStoreResult.STORED) return@withContext commitResult
            destination.setLastModified(System.currentTimeMillis())
            thumbnailIndexByDirectory[directory.absolutePath]?.set(timestampSeconds, destination)
            directory.setLastModified(System.currentTimeMillis())
            writesSinceTrim++
            if (writesSinceTrim >= TRIM_EVERY_WRITES) {
                trimToSize()
                writesSinceTrim = 0
            }
            ThumbnailStoreResult.STORED
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /** Protect the current title's frames while its full-timeline cache is being built. */
    suspend fun beginWorkingSet(profileId: Int, contentId: String): ThumbnailWorkingSetStatus =
        withContext(ioDispatcher) {
            if (profileId <= 0 || contentId.isBlank()) {
                return@withContext ThumbnailWorkingSetStatus(
                    admitted = false,
                    currentBytes = 0L,
                    limitBytes = maxWorkingSetBytes,
                    refusalReason = "invalid_identity"
                )
            }
            val directory = contentDirectory(profileId, contentId)
            runCatching {
                withWorkingSetRegistryLock {
                    val path = directory.absolutePath
                    val currentBytes = directoryBytes(directory)
                    if (currentBytes > maxWorkingSetBytes) {
                        return@withWorkingSetRegistryLock ThumbnailWorkingSetStatus(
                            false, currentBytes, maxWorkingSetBytes, "existing_timeline_exceeds_limit"
                        )
                    }
                    val currentLease = pinnedWorkingSets[path]
                    if (currentLease != null) {
                        currentLease.references++
                        return@withWorkingSetRegistryLock ThumbnailWorkingSetStatus(
                            true, currentBytes, maxWorkingSetBytes
                        )
                    }
                    val lease = openWorkingSetLease(directory)
                    if (lease == null) {
                        ThumbnailWorkingSetStatus(
                            false, currentBytes, maxWorkingSetBytes, "timeline_in_use_or_lock_unavailable"
                        )
                    } else {
                        pinnedWorkingSets[path] = lease
                        ThumbnailWorkingSetStatus(true, currentBytes, maxWorkingSetBytes)
                    }
                }
            }.getOrElse {
                ThumbnailWorkingSetStatus(
                    false,
                    directoryBytes(directory),
                    maxWorkingSetBytes,
                    "working_set_lock_unavailable"
                )
            }
        }

    suspend fun endWorkingSet(profileId: Int, contentId: String) = withContext(NonCancellable + ioDispatcher) {
        if (profileId > 0 && contentId.isNotBlank()) {
            withWorkingSetRegistryLock {
                val directory = contentDirectory(profileId, contentId)
                val path = directory.absolutePath
                val lease = pinnedWorkingSets[path]
                if (lease != null && --lease.references == 0) {
                    pinnedWorkingSets.remove(path)
                    lease.close()
                    workingSetLeaseFile(directory).delete()
                }
                trimToSizeLocked()
            }
        }
    }

    suspend fun loadNearest(
        profileId: Int,
        contentId: String,
        positionMs: Long,
        intervalSeconds: Int
    ): Bitmap? = withContext(ioDispatcher) {
        if (profileId <= 0 || contentId.isBlank()) return@withContext null
        val targetSeconds = ThumbnailTimelinePolicy.gridPosition(positionMs, normalizeInterval(intervalSeconds)) / 1_000L
        val directory = if (ThumbnailTimelinePolicy.isSourceScoped(contentId)) contentDirectory(profileId, contentId)
            else profileDirectory(profileId).listFiles().orEmpty()
                .filter { decodeContentId(it.name)?.let { id -> ThumbnailTimelinePolicy.belongsTo(id, contentId) } == true }
                .maxByOrNull { it.lastModified() } ?: contentDirectory(profileId, contentId)
        val nearest = thumbnailIndex(directory)[targetSeconds]?.takeIf { it.exists() }
            ?: return@withContext null
        nearest.setLastModified(System.currentTimeMillis())
        BitmapFactory.decodeFile(nearest.absolutePath)
    }

    suspend fun progressPercent(
        profileId: Int,
        contentId: String,
        durationMs: Long,
        intervalSeconds: Int
    ): Int = progress(profileId, contentId, durationMs, intervalSeconds).percent

    suspend fun progress(
        profileId: Int,
        contentId: String,
        durationMs: Long,
        intervalSeconds: Int
    ): SeekThumbnailProgress = withContext(ioDispatcher) {
        if (profileId <= 0 || contentId.isBlank() || durationMs <= 0L) {
            return@withContext SeekThumbnailProgress(0, 0, 0)
        }
        val targets = storyboardTargets(durationMs, intervalSeconds)
        if (targets.isEmpty()) return@withContext SeekThumbnailProgress(0, 0, 0)
        val expectedSeconds = targets.asSequence().map { it / 1_000L }.toHashSet()
        val directory = contentDirectory(profileId, contentId)
        val cached = thumbnailIndex(directory).count { (seconds, file) ->
            seconds in expectedSeconds && file.exists()
        }
        SeekThumbnailProgress(
            cachedFrames = cached,
            totalFrames = targets.size,
            percent = calculateProgressPercent(cached, targets.size)
        )
    }

    suspend fun contains(
        profileId: Int,
        contentId: String,
        positionMs: Long,
        intervalSeconds: Int
    ): Boolean = withContext(ioDispatcher) {
        if (profileId <= 0 || contentId.isBlank()) return@withContext false
        val timestampSeconds = quantizePositionSeconds(positionMs, intervalSeconds)
        val directory = contentDirectory(profileId, contentId)
        thumbnailIndex(directory)[timestampSeconds]?.exists() == true
    }

    suspend fun clearContent(profileId: Int, contentId: String) = withContext(ioDispatcher) {
        if (profileId > 0 && contentId.isNotBlank()) {
            profileDirectory(profileId).listFiles().orEmpty().filter {
                decodeContentId(it.name)?.let { id -> ThumbnailTimelinePolicy.belongsTo(id, contentId) } == true
            }.forEach { directory ->
                thumbnailIndexByDirectory.remove(directory.absolutePath)
                lastDirectoryScanElapsedMs.remove(directory.absolutePath)
                directory.deleteRecursively()
            }
        }
    }

    suspend fun clearContentPrefix(profileId: Int, contentIdPrefix: String) = withContext(ioDispatcher) {
        if (profileId <= 0 || contentIdPrefix.isBlank()) return@withContext
        profileDirectory(profileId).listFiles().orEmpty().forEach { directory ->
            val decodedId = decodeContentId(directory.name) ?: return@forEach
            if (decodedId.startsWith(contentIdPrefix)) {
                thumbnailIndexByDirectory.remove(directory.absolutePath)
                lastDirectoryScanElapsedMs.remove(directory.absolutePath)
                directory.deleteRecursively()
            }
        }
    }

    suspend fun clearProfile(profileId: Int) = withContext(ioDispatcher) {
        if (profileId > 0) {
            val directory = profileDirectory(profileId)
            thumbnailIndexByDirectory.keys.removeAll { it.startsWith(directory.absolutePath) }
            lastDirectoryScanElapsedMs.keys.removeAll { it.startsWith(directory.absolutePath) }
            directory.deleteRecursively()
        }
    }

    /**
     * The isolated worker is the only writer in its process, so one initial disk
     * scan is enough. Avoiding a directory listing before every frame prevents
     * generation from becoming progressively slower as the cache grows.
     */
    fun optimizeForLocalWriter() {
        localWriterMode = true
    }

    private fun trimToSize() {
        withWorkingSetRegistryLock { trimToSizeLocked() }
    }

    private fun trimToSizeLocked() {
        cleanStaleWorkingSetLeasesLocked()
        val files = installationRoot.walkTopDown()
            .filter { it.isFile && it.extension == "webp" }
            .sortedBy { it.lastModified() }
            .toList()
        var totalBytes = files.sumOf { it.length() }
        for (file in files) {
            if (totalBytes <= maxCacheBytes) break
            if (file.parentFile?.let(::isDirectoryPinnedLocked) == true) continue
            val length = file.length()
            if (file.delete()) {
                totalBytes -= length
                thumbnailIndexByDirectory.values.forEach { index ->
                    index.entries.removeAll { it.value == file }
                }
            }
        }
    }

    private fun <T> withWorkingSetRegistryLock(block: () -> T): T =
        synchronized(PROCESS_WORKING_SET_LOCK) {
            installationRoot.mkdirs()
            RandomAccessFile(File(installationRoot, WORKING_SET_REGISTRY_LOCK), "rw").use { file ->
                file.channel.lock().use { block() }
            }
        }

    private fun openWorkingSetLease(directory: File): WorkingSetLease? {
        val file = workingSetLeaseFile(directory)
        if (file.parentFile?.exists() != true && file.parentFile?.mkdirs() != true) return null
        val handle = runCatching { RandomAccessFile(file, "rw") }.getOrNull() ?: return null
        val lock = try {
            handle.channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        } catch (_: IOException) {
            null
        }
        if (lock == null) {
            runCatching { handle.close() }
            return null
        }
        return WorkingSetLease(handle, lock)
    }

    /** Caller holds the registry lock, so an idle marker cannot race a new admission. */
    private fun isDirectoryPinnedLocked(directory: File): Boolean {
        if (directory.absolutePath in pinnedWorkingSets) return true
        val marker = workingSetLeaseFile(directory)
        if (!marker.exists()) return false
        val handle = runCatching { RandomAccessFile(marker, "rw") }.getOrNull() ?: return true
        val lock = try {
            handle.channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        } catch (_: IOException) {
            null
        }
        if (lock == null) {
            runCatching { handle.close() }
            return true
        }
        runCatching { lock.release() }
        runCatching { handle.close() }
        marker.delete()
        return false
    }

    private fun cleanStaleWorkingSetLeasesLocked() {
        workingSetLeaseRoot.listFiles().orEmpty().forEach { marker ->
            val handle = runCatching { RandomAccessFile(marker, "rw") }.getOrNull() ?: return@forEach
            val lock = try {
                handle.channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            } catch (_: IOException) {
                null
            }
            if (lock != null) {
                runCatching { lock.release() }
                runCatching { handle.close() }
                marker.delete()
            } else {
                runCatching { handle.close() }
            }
        }
    }

    private fun workingSetLeaseFile(directory: File): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(directory.absolutePath.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return File(workingSetLeaseRoot, "$digest.lock")
    }

    private val workingSetLeaseRoot: File
        get() = File(installationRoot, WORKING_SET_LEASE_DIRECTORY)

    private data class WorkingSetLease(
        private val handle: RandomAccessFile,
        private val lock: FileLock,
        var references: Int = 1
    ) {
        fun close() {
            runCatching { lock.release() }
            runCatching { handle.close() }
        }
    }

    private fun directoryBytes(directory: File): Long =
        directory.listFiles { file -> file.isFile && file.extension == "webp" }
            .orEmpty()
            .sumOf(File::length)

    private fun thumbnailIndex(directory: File): MutableMap<Long, File> {
        val path = directory.absolutePath
        val index = thumbnailIndexByDirectory.getOrPut(path) { mutableMapOf() }
        val now = SystemClock.elapsedRealtime()
        val lastScan = lastDirectoryScanElapsedMs[path]
        val shouldScan = lastScan == null ||
            (!localWriterMode && now - lastScan >= DIRECTORY_RESCAN_INTERVAL_MS)
        if (shouldScan) {
            index.clear()
            directory.listFiles { file -> file.isFile && file.extension == "webp" }
                .orEmpty()
                .forEach { file ->
                    file.nameWithoutExtension.toLongOrNull()?.let { timestamp ->
                        index[timestamp] = file
                    }
                }
            lastDirectoryScanElapsedMs[path] = now
        }
        return index
    }

    private fun contentDirectory(profileId: Int, contentId: String): File =
        File(profileDirectory(profileId), encodeContentId(contentId))

    private fun profileDirectory(profileId: Int): File = File(root, profileId.toString())

    private fun encodeContentId(contentId: String): String = Base64.encodeToString(
        contentId.toByteArray(Charsets.UTF_8),
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    )

    private fun decodeContentId(encoded: String): String? = runCatching {
        String(
            Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
            Charsets.UTF_8
        )
    }.getOrNull()

    companion object {
        private const val CACHE_DIRECTORY = "seek_thumbnails_v5"
        private val OBSOLETE_CACHE_DIRECTORIES = listOf("seek_thumbnails", "seek_thumbnails_v2", "seek_thumbnails_v3", "seek_thumbnails_v4")
        private const val WEBP_QUALITY = 75
        private const val TRIM_EVERY_WRITES = 50
        private const val MAX_ACTIVE_WORKING_SET_BYTES = 64L * 1024L * 1024L
        private const val DIRECTORY_RESCAN_INTERVAL_MS = 400L
        private const val WORKING_SET_REGISTRY_LOCK = ".working-set-registry.lock"
        private const val WORKING_SET_LEASE_DIRECTORY = ".working-sets"
        private val PROCESS_WORKING_SET_LOCK = Any()
        val SUPPORTED_INTERVALS = setOf(10, 20, 30)

        private fun deviceLimits(context: Context): CacheLimits {
            val cacheBytes = InstallationCacheBudget.forContext(context).seekThumbnailBytes
            return CacheLimits(cacheBytes, minOf(cacheBytes / 4, MAX_ACTIVE_WORKING_SET_BYTES))
        }

        fun normalizeInterval(seconds: Int): Int = seconds.takeIf(SUPPORTED_INTERVALS::contains) ?: 10

        fun quantizePositionSeconds(positionMs: Long, intervalSeconds: Int): Long {
            val interval = normalizeInterval(intervalSeconds).toLong()
            val seconds = positionMs.coerceAtLeast(0L) / 1_000L
            return (seconds / interval) * interval
        }

        /** Every configured interval, in playback order. */
        fun storyboardTargets(durationMs: Long, intervalSeconds: Int): List<Long> {
            if (durationMs <= 0L) return emptyList()
            val intervalMs = normalizeInterval(intervalSeconds) * 1_000L
            val lastTarget = (durationMs - 1_000L).coerceAtLeast(0L)
            return generateSequence(0L) { previous ->
                (previous + intervalMs).takeIf { it <= lastTarget }
            }.toList()
        }

        /**
         * Limits aggressive startup extraction to the beginning of the video.
         * Rendered playback frames can extend the cache without a second decoder.
         */
        fun startupStoryboardTargets(
            durationMs: Long,
            intervalSeconds: Int,
            startupWindowMs: Long
        ): List<Long> = storyboardTargets(durationMs, intervalSeconds)
            .takeWhile { it <= startupWindowMs.coerceAtLeast(0L) }

        /** Fast visible coverage first, followed by full-resolution refinement. */
        fun progressiveStoryboardTargets(durationMs: Long, intervalSeconds: Int): List<Long> {
            val normalizedInterval = normalizeInterval(intervalSeconds)
            // Coarse timestamps must align with final cache buckets. Thirty-second
            // sampling aligns with 10 seconds, while 20 and 30 already are coarse.
            val coarseInterval = if (normalizedInterval == 10) 30 else normalizedInterval
            return buildList {
                addAll(startupStoryboardTargets(durationMs, normalizedInterval, 5L * 60L * 1_000L))
                addAll(storyboardTargets(durationMs, coarseInterval))
                if (normalizedInterval < coarseInterval) {
                    addAll(storyboardTargets(durationMs, normalizedInterval))
                }
            }.distinct()
        }

        /**
         * ThumbFast-style persistent decoders start with useful seek positions.
         * Timestamp zero is commonly a black pre-roll frame, so it is filled last.
         */
        fun persistentDecoderTargets(
            durationMs: Long,
            intervalSeconds: Int,
            priorityPositionMs: Long = 0L
        ): List<Long> {
            val targets = storyboardTargets(durationMs, intervalSeconds)
            if (priorityPositionMs <= 0L) {
                // First ten useful previews first, then keep reading forward.
                // A coarse sweep followed by a backwards refinement pass forces
                // a second traversal of a remote file and slows complete caching.
                return targets.filterNot { it == 0L } + targets.filter { it == 0L }
            }
            val all = storyboardTargets(durationMs, intervalSeconds)
            val anchor = quantizePositionSeconds(priorityPositionMs, intervalSeconds) * 1_000L
            val step = normalizeInterval(intervalSeconds) * 1_000L
            // Five carousel frames first, then monotonic forward reads, followed
            // by earlier gaps. No timestamp is lost when the priority changes.
            val nearby = listOf(anchor, anchor + step, anchor + 2 * step, anchor - step, anchor - 2 * step)
                .filter { it in all }
            return (nearby + all.filter { it >= anchor } + all.filter { it < anchor }).distinct()
        }

        fun calculateProgressPercent(cachedFrames: Int, totalFrames: Int): Int {
            if (cachedFrames <= 0 || totalFrames <= 0) return 0
            return kotlin.math.ceil(cachedFrames * 100.0 / totalFrames)
                .toInt()
                .coerceIn(1, 100)
        }
    }

    private data class CacheLimits(val cacheBytes: Long, val workingSetBytes: Long) {
        init {
            require(cacheBytes > 0L)
            require(workingSetBytes > 0L && workingSetBytes <= cacheBytes)
        }
    }
}
