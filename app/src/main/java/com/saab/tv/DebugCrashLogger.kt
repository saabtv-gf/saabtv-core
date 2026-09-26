package com.saab.tv

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.util.Date

/** Persists the most recent uncaught JVM stack trace in debuggable builds. */
object DebugCrashLogger {
    @Volatile
    private var installed = false

    @Synchronized
    fun install(context: Context) {
        if (installed) return
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        val applicationContext = context.applicationContext

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val directory = File(applicationContext.filesDir, "crash_logs")
                if (directory.exists() || directory.mkdirs()) {
                    PrintWriter(File(directory, "latest_crash.txt")).use { writer ->
                        writer.println("Saab TV ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                        writer.println("Captured: ${Date()}")
                        writer.println("Thread: ${thread.name}")
                        writer.println()
                        throwable.printStackTrace(writer)
                    }
                }
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
        installed = true
    }
}
