package com.saab.tv

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import com.saab.tv.data.player.PlaybackDiagnosticEvent
import com.saab.tv.data.player.PlaybackDiagnosticReport
import com.saab.tv.data.player.PlaybackDiagnostics
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Device-local, bounded diagnostics. Never supply request bodies, credentials or route arguments. */
object AppDiagnostics {
    @Volatile private var appContext: Context? = null
    private const val LIMIT = 1024L * 1024
    private val writer = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
        ArrayBlockingQueue<Runnable>(256), { task -> Thread(task, "app-diagnostics").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardPolicy())

    fun event(component: String, event: String, details: String = "") {
        appContext?.let { event(it, component, event, details) }
    }

    fun failure(component: String, event: String, failure: Throwable) {
        appContext?.let { failure(it, component, event, failure) }
    }

    fun isBasicEnabled(context: Context): Boolean = context.getSharedPreferences("app_diagnostics_settings", Context.MODE_PRIVATE).getBoolean("basic", false)
    fun setBasicEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("app_diagnostics_settings", Context.MODE_PRIVATE).edit().putBoolean("basic", enabled).apply()
    }
    fun recordsEvents(context: Context): Boolean = isBasicEnabled(context) || PlaybackDiagnostics.isEnabled(context)

    fun event(context: Context, component: String, event: String, details: String = "", important: Boolean = false) {
        if (!((isBasicEnabled(context) && (important || component != "Network")) || PlaybackDiagnostics.isEnabled(context))) return
        val app = context.applicationContext
        val item = PlaybackDiagnosticEvent(System.currentTimeMillis(), "INFO", component, event, DiagnosticPrivacy.redact(details).take(8000))
        writer.execute { append(app, item) }
    }

    /** A fatal event must reach disk before Android terminates the process. */
    fun failure(context: Context, component: String, event: String, failure: Throwable) {
        append(context.applicationContext, PlaybackDiagnosticEvent(System.currentTimeMillis(), "ERROR", component, event,
            DiagnosticPrivacy.stack(failure)))
    }

    fun install(application: Application) {
        appContext = application.applicationContext
        event(application, "Application", "Process Started",
            "version=${BuildConfig.VERSION_NAME} sdk=${android.os.Build.VERSION.SDK_INT} ${PlaybackDiagnostics.memorySummary()}", true)
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private fun log(activity: Activity, state: String) = event(application, "Lifecycle", state,
                "activity=${activity.javaClass.simpleName}")
            override fun onActivityCreated(activity: Activity, state: Bundle?) = log(activity, "Created")
            override fun onActivityStarted(activity: Activity) = log(activity, "Started")
            override fun onActivityResumed(activity: Activity) = log(activity, "Resumed")
            override fun onActivityPaused(activity: Activity) = log(activity, "Paused")
            override fun onActivityStopped(activity: Activity) = log(activity, "Stopped")
            override fun onActivityDestroyed(activity: Activity) = log(activity, "Destroyed")
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        })
    }

    @Synchronized private fun append(context: Context, item: PlaybackDiagnosticEvent) {
        runCatching {
            val directory = File(context.filesDir, "app_diagnostics").apply { mkdirs() }
            val file = File(directory, "events.log")
            if (file.length() >= LIMIT) {
                val previous = File(directory, "previous.log")
                previous.delete()
                file.renameTo(previous)
            }
            file.appendText(listOf(item.timestampMs.toString(), item.level, item.component, item.event,
                item.details).joinToString("\t") { DiagnosticPrivacy.redact(it).replace('\n', ' ').replace('\t', ' ').take(8000) } + "\n")
        }
    }

    fun report(context: Context, maxEvents: Int = 500): PlaybackDiagnosticReport {
        val directory = File(context.filesDir, "app_diagnostics")
        val events = listOf("previous.log", "events.log").flatMap { name ->
            runCatching { File(directory, name).readLines().mapNotNull { line ->
                val fields = line.split('\t', limit = 5)
                val time = fields.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                if (fields.size != 5) return@mapNotNull null
                PlaybackDiagnosticEvent(time, fields[1], fields[2], fields[3], fields[4])
            } }.getOrDefault(emptyList())
        } + PlaybackDiagnostics.latestReport(context)?.events.orEmpty() +
            runCatching { AppHealthMonitor.latestSummary(context).mapNotNull { line ->
                val time = line.substringBefore(' ').toLongOrNull() ?: return@mapNotNull null
                PlaybackDiagnosticEvent(time, "INFO", "App Health", "Historical Event", DiagnosticPrivacy.redact(line.substringAfter(' ')))
            } }.getOrDefault(emptyList())
        return PlaybackDiagnosticReport("app", "Application And Latest Playback",
            events.minOfOrNull { it.timestampMs } ?: System.currentTimeMillis(), events.sortedByDescending { it.timestampMs }.take(maxEvents))
    }

    fun export(context: Context): File? = runCatching {
        val output = File(context.cacheDir, "diagnostics_exports/saab-tv-app-diagnostics.txt")
        output.parentFile?.mkdirs()
        output.bufferedWriter().use { out ->
            out.appendLine("Saab TV App Diagnostics — ${BuildConfig.VERSION_NAME}")
            out.appendLine("Local bounded history; basic=${isBasicEnabled(context)} detailed=${PlaybackDiagnostics.isEnabled(context)}")
            out.appendLine("SDK=${android.os.Build.VERSION.SDK_INT} ${PlaybackDiagnostics.memorySummary()}")
            out.appendLine("Application Health")
            AppHealthMonitor.latestSummary(context).forEach { out.appendLine(DiagnosticPrivacy.redact(it)) }
            out.appendLine("Application And Latest Playback Events")
            report(context, Int.MAX_VALUE).events.asReversed().forEach { item ->
                out.appendLine("${PlaybackDiagnostics.formatTimestamp(item.timestampMs)} ${item.level} ${item.component} ${item.event} ${DiagnosticPrivacy.redact(item.details)}")
            }
        }
        output
    }.getOrNull()

    fun clear(context: Context) {
        // Ordered after pending writes; restrict deletion to our own known diagnostic files.
        writer.queue.clear()
        synchronized(this) { File(context.filesDir, "app_diagnostics").deleteRecursively() }
        AppHealthMonitor.clear(context)
        PlaybackDiagnostics.clear(context)
    }
}
