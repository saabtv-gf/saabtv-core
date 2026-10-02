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

    fun detailed(component: String, event: String, details: String = "") {
        appContext?.let { event(it, component, event, details, detailedOnly = true) }
    }

    fun failure(component: String, event: String, failure: Throwable) {
        appContext?.let { failure(it, component, event, failure) }
    }

    fun isBasicEnabled(context: Context): Boolean = context.getSharedPreferences("app_diagnostics_settings", Context.MODE_PRIVATE).getBoolean("basic", false)
    fun setBasicEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("app_diagnostics_settings", Context.MODE_PRIVATE).edit().putBoolean("basic", enabled).apply()
    }
    fun recordsEvents(context: Context): Boolean = isBasicEnabled(context) || PlaybackDiagnostics.isEnabled(context)

    fun event(context: Context, component: String, event: String, details: String = "", important: Boolean = false,
        detailedOnly: Boolean = false) {
        if (!DiagnosticRecordingPolicy.records(isBasicEnabled(context), PlaybackDiagnostics.isEnabled(context), component, detailedOnly)) return
        val app = context.applicationContext
        val item = PlaybackDiagnosticEvent(System.currentTimeMillis(), "INFO", component, event, DiagnosticPrivacy.redact(details).take(8000))
        writer.execute { append(app, item) }
    }

    /** Basic opt-in TorBox state trail; never keys, hashes or URLs. */
    fun torBoxEvent(event: String, details: String) {
        appContext?.let { torBoxEvent(it, event, details) }
    }

    fun torBoxEvent(context: Context, event: String, details: String) {
        event(context, "TorBox", event, details.take(800))
    }

    /** A fatal event must reach disk before Android terminates the process. */
    fun failure(context: Context, component: String, event: String, failure: Throwable) {
        val fatal = DiagnosticRecordingPolicy.fatal(component, event)
        if (!fatal && (DiagnosticRecordingPolicy.cancellation(failure) ||
                !DiagnosticRecordingPolicy.records(isBasicEnabled(context), PlaybackDiagnostics.isEnabled(context), component))) return
        val app = context.applicationContext
        val item = PlaybackDiagnosticEvent(System.currentTimeMillis(), if (fatal) "FATAL" else "WARN", component, event,
            DiagnosticPrivacy.stack(failure))
        if (fatal) append(app, item)
        else writer.execute { append(app, item) }
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
        } + listOf(PlaybackDiagnosticEvent(System.currentTimeMillis(), "INFO", "Cloud Sync", "Transfer Summary",
            com.saab.tv.data.account.AccountTransferMetrics.summary(context))) +
            PlaybackDiagnostics.latestReport(context)?.events.orEmpty() +
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
            out.appendLine(com.saab.tv.data.account.AccountTransferMetrics.summary(context))
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
