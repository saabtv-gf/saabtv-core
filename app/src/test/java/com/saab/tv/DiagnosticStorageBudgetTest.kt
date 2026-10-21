package com.saab.tv

import android.app.Application
import com.saab.tv.data.player.PlaybackDiagnostics
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DiagnosticStorageBudgetTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun persistedLogsStayUnderTheSharedBudgetAndKeepTheNewestHistory() {
        val old = File(context.filesDir, "app_diagnostics/previous.log").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(4 * 1024 * 1024))
            setLastModified(System.currentTimeMillis() - 10_000L)
        }
        val recent = File(context.filesDir, "playback_diagnostics/session-1/worker.log").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(3 * 1024 * 1024))
            setLastModified(System.currentTimeMillis())
        }

        DiagnosticStorageBudget.pruneNow(context)

        val retainedBytes = listOf("app_diagnostics", "playback_diagnostics", "app_health")
            .map { File(context.filesDir, it) }
            .sumOf { root -> root.walkTopDown().filter(File::isFile).sumOf(File::length) }
        assertFalse(old.exists())
        assertTrue(recent.exists())
        assertTrue(retainedBytes <= DiagnosticStorageBudget.MAX_PERSISTED_BYTES)
    }

    @Test fun expiredHealthAndExportFilesAreRemovedWithoutRemovingRecentOnes() {
        val expiredAt = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(8)
        val expiredHealth = File(context.filesDir, "app_health/previous.txt").apply {
            parentFile?.mkdirs()
            writeText("old")
            setLastModified(expiredAt)
        }
        val recentHealth = File(context.filesDir, "app_health/latest.txt").apply {
            writeText("recent")
        }
        val expiredExport = File(context.cacheDir, "diagnostics_exports/old.txt").apply {
            parentFile?.mkdirs()
            writeText("old")
            setLastModified(expiredAt)
        }
        val recentExport = File(context.cacheDir, "diagnostics_exports/current.txt").apply {
            writeText("recent")
        }

        DiagnosticStorageBudget.pruneNow(context)

        assertFalse(expiredHealth.exists())
        assertTrue(recentHealth.exists())
        assertFalse(expiredExport.exists())
        assertTrue(recentExport.exists())
    }

    @Test fun exportScratchStaysUnderItsSeparateBudget() {
        val old = File(context.cacheDir, "diagnostics_exports/old.txt").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(1_500 * 1024))
            setLastModified(System.currentTimeMillis() - 10_000L)
        }
        val recent = File(context.cacheDir, "diagnostics_exports/current.txt").apply {
            writeBytes(ByteArray(1_500 * 1024))
            setLastModified(System.currentTimeMillis())
        }

        DiagnosticStorageBudget.pruneNow(context)

        val retainedBytes = File(context.cacheDir, "diagnostics_exports")
            .walkTopDown().filter(File::isFile).sumOf(File::length)
        assertFalse(old.exists())
        assertTrue(recent.exists())
        assertTrue(retainedBytes <= DiagnosticStorageBudget.MAX_EXPORT_BYTES)
    }

    @Test fun activePlaybackSessionIsRetainedUsingItsRecentLogTimestamp() {
        val session = File(context.filesDir, "playback_diagnostics/old-session")
        val metadata = File(session, "session.meta").apply {
            parentFile?.mkdirs()
            writeText("old session")
            setLastModified(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(8))
        }
        val activeLog = File(session, "worker.log").apply {
            writeText("recent append")
            setLastModified(System.currentTimeMillis())
        }
        session.setLastModified(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(8))

        DiagnosticStorageBudget.pruneNow(context)

        assertTrue(metadata.exists())
        assertTrue(activeLog.exists())
    }

    @Test fun playbackSessionIdCannotEscapeTheDiagnosticsDirectory() {
        PlaybackDiagnostics.setEnabled(context, true)
        val root = File(context.filesDir, "playback_diagnostics")

        PlaybackDiagnostics.beginSession(
            context = context,
            sessionId = "../../outside",
            title = "Path check",
            player = "test",
            mediaUrl = "https://example.test/video"
        )

        val session = root.listFiles { file -> file.isDirectory }.orEmpty().single()
        assertTrue(session.canonicalFile.parentFile == root.canonicalFile)
        assertTrue(File(context.filesDir, "outside/session.meta").exists().not())
    }
}
