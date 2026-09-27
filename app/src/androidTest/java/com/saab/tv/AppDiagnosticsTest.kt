package com.saab.tv

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDiagnosticsTest {
    @Test fun crashCanBeViewedAndExportedWithoutPlayback() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(base.cacheDir, "diagnostics-test-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(directory, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(directory, "cache").apply { mkdirs() }
        }
        try {
            AppDiagnostics.failure(context, "Updater", "Test Failure", IllegalStateException("password=secret"))
            val report = AppDiagnostics.report(context)
            assertTrue(report.events.any { it.event == "Test Failure" && it.details.contains("IllegalStateException") })
            val export = requireNotNull(AppDiagnostics.export(context)).readText()
            assertTrue(export.contains("Test Failure"))
            assertFalse(export.contains("password=secret"))
            assertFalse(export.contains("secret"))
        } finally { directory.deleteRecursively() }
    }
}
