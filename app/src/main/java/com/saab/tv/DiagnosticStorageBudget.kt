package com.saab.tv

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.io.IOException
import java.nio.channels.OverlappingFileLockException

/** Shared byte/age limits for app, playback and process-exit diagnostics. */
internal object DiagnosticStorageBudget {
    const val MAX_PERSISTED_BYTES = 6L * 1024L * 1024L
    const val MAX_EXPORT_BYTES = 2L * 1024L * 1024L
    private const val MAX_AGE_MS = 7L * 24L * 60L * 60L * 1_000L
    private const val LOCK_FILE = ".diagnostics-storage.lock"
    private val processLock = Any()

    fun <T> withLock(context: Context, block: () -> T): T = synchronized(processLock) {
        val app = context.applicationContext
        val lockFile = File(app.filesDir, LOCK_FILE)
        val handle = runCatching {
            lockFile.parentFile?.mkdirs()
            RandomAccessFile(lockFile, "rw")
        }.getOrNull()
        if (handle == null) return@synchronized runAndPrune(app, block)

        val lock = try {
            handle.channel.lock()
        } catch (_: OverlappingFileLockException) {
            null
        } catch (_: IOException) {
            null
        }
        if (lock == null) {
            runCatching { handle.close() }
            return@synchronized runAndPrune(app, block)
        }
        try {
            runAndPrune(app, block)
        } finally {
            runCatching { lock.release() }
            runCatching { handle.close() }
        }
    }

    /** Exposed to focused tests; production writes call this automatically under the lock. */
    fun pruneNow(context: Context) {
        withLock(context) { Unit }
    }

    private fun <T> runAndPrune(context: Context, block: () -> T): T = try {
        block()
    } finally {
        runCatching { prune(context) }
    }

    private fun prune(context: Context) {
        val now = System.currentTimeMillis()
        val persistentRoots = listOf(
            File(context.filesDir, "app_diagnostics"),
            File(context.filesDir, "playback_diagnostics"),
            File(context.filesDir, "app_health")
        )
        val playbackRoot = persistentRoots[1]

        // Directory timestamps are not reliably refreshed when an existing child file is
        // appended to. Use the newest child timestamp so active sessions do not expire early.
        playbackRoot.listFiles { file -> file.isDirectory }.orEmpty().forEach { session ->
            val mostRecentWrite = session.walkTopDown()
                .filter(File::isFile)
                .maxOfOrNull(File::lastModified)
                ?: session.lastModified()
            if (now - mostRecentWrite > MAX_AGE_MS) session.deleteRecursively()
        }
        persistentRoots.filter { it != playbackRoot }.forEach { root ->
            root.walkTopDown().filter(File::isFile).forEach { file ->
                if (now - file.lastModified() > MAX_AGE_MS) file.delete()
            }
        }

        trimFiles(persistentRoots, MAX_PERSISTED_BYTES)

        val exportRoot = File(context.cacheDir, "diagnostics_exports")
        exportRoot.listFiles().orEmpty().filter(File::isFile).forEach { file ->
            if (now - file.lastModified() > MAX_AGE_MS) file.delete()
        }
        trimFiles(listOf(exportRoot), MAX_EXPORT_BYTES)

        persistentRoots.forEach(::removeEmptyDirectories)
    }

    private fun trimFiles(roots: List<File>, limitBytes: Long) {
        val files = roots.asSequence()
            .filter(File::exists)
            .flatMap { root -> root.walkTopDown().asSequence() }
            .filter(File::isFile)
            .toList()
        var total = files.sumOf(File::length)
        if (total <= limitBytes) return

        // Retain recent event logs ahead of session labels when reducing storage.
        val oldestFirst = files.sortedWith(
            compareBy<File> { it.name == "session.meta" }
                .thenBy(File::lastModified)
        )
        for (file in oldestFirst) {
            if (total <= limitBytes) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    private fun removeEmptyDirectories(directory: File) {
        directory.listFiles().orEmpty().filter(File::isDirectory).forEach(::removeEmptyDirectories)
        if (directory.name != "app_diagnostics" && directory.name != "playback_diagnostics" &&
            directory.name != "app_health" && directory.listFiles().isNullOrEmpty()) {
            directory.delete()
        }
    }
}
