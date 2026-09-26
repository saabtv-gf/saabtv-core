package com.saab.tv

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.util.Date

/** Persists local evidence for crashes, ANRs, low-memory kills and native exits. */
object AppHealthMonitor {
    private const val DIRECTORY = "app_health"
    private const val MAX_TRACE_LINES = 80

    fun install(context: Context) {
        capturePreviousExits(context.applicationContext)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            append(
                context,
                "uncaught thread=${thread.name} type=${throwable.javaClass.name} " +
                    "message=${throwable.message.orEmpty()}\n${throwable.stackTraceToString().take(32_000)}"
            )
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun recordMemoryPressure(context: Context, level: Int) {
        append(context, "memory-pressure level=$level ${memorySummary()}")
    }

    fun latestSummary(context: Context): List<String> =
        healthFile(context).takeIf(File::isFile)?.readLines()?.takeLast(180).orEmpty()

    private fun capturePreviousExits(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        Thread({
            val manager = context.getSystemService(ActivityManager::class.java) ?: return@Thread
            val exits = runCatching {
                manager.getHistoricalProcessExitReasons(context.packageName, 0, 5)
            }.getOrDefault(emptyList())
            exits.forEach { exit ->
                val reason = when (exit.reason) {
                    ApplicationExitInfo.REASON_ANR -> "ANR"
                    ApplicationExitInfo.REASON_CRASH -> "CRASH"
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE_CRASH"
                    ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
                    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "RESOURCE_LIMIT"
                    ApplicationExitInfo.REASON_SIGNALED -> "SIGNALLED"
                    else -> "REASON_${exit.reason}"
                }
                val header = "previous-exit timestamp=${Date(exit.timestamp)} reason=$reason " +
                    "status=${exit.status} importance=${exit.importance} pssKb=${exit.pss} " +
                    "rssKb=${exit.rss} description=${exit.description.orEmpty()}"
                val trace = runCatching {
                    exit.traceInputStream?.bufferedReader()?.useLines { lines ->
                        lines.take(MAX_TRACE_LINES).joinToString("\n")
                    }.orEmpty()
                }.getOrDefault("")
                append(context, if (trace.isBlank()) header else "$header\n$trace")
            }
        }, "app-health-history").apply {
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    @Synchronized
    private fun append(context: Context, value: String) {
        runCatching {
            val file = healthFile(context)
            file.parentFile?.mkdirs()
            if (file.length() > 512L * 1024L) {
                File(file.parentFile, "previous.txt").also { previous ->
                    previous.delete()
                    file.renameTo(previous)
                }
            }
            file.appendText("${System.currentTimeMillis()} $value\n")
        }
    }

    private fun healthFile(context: Context): File =
        File(File(context.filesDir, DIRECTORY), "latest.txt")

    private fun memorySummary(): String {
        val runtime = Runtime.getRuntime()
        val used = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)
        return "java=${used}MB/${runtime.maxMemory() / (1024L * 1024L)}MB"
    }
}
