package com.saab.tv.data.player

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Process
import com.saab.tv.AppHealthMonitor
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class PlaybackDiagnosticEvent(
    val timestampMs: Long,
    val level: String,
    val component: String,
    val event: String,
    val details: String
)

data class PlaybackDiagnosticReport(
    val sessionId: String,
    val title: String,
    val startedAtMs: Long,
    val events: List<PlaybackDiagnosticEvent>
)

/**
 * Small, local-only playback event recorder. The player and isolated thumbnail
 * worker use separate append-only files inside one session directory so neither
 * process can block or corrupt the other's log.
 */
object PlaybackDiagnostics {
    private const val PREFERENCES = "playback_diagnostics_settings"
    private const val ENABLED_KEY = "enabled"
    private const val DIRECTORY = "playback_diagnostics"
    private const val EXPORT_DIRECTORY = "diagnostics_exports"
    private const val METADATA_FILE = "session.meta"
    private const val MAX_SESSIONS = 10
    private const val MAX_LOG_BYTES = 512L * 1024L
    private const val MAX_PANEL_EVENTS = 250
    @Volatile private var enabledCache: Boolean? = null

    fun newSessionId(): String =
        "${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"

    /** Diagnostic recording is opt-in because per-frame logging adds disk I/O. */
    fun isEnabled(context: Context): Boolean {
        enabledCache?.let { return it }
        return com.saab.tv.data.account.AccountStorage.preferences(context, PREFERENCES)
            .getBoolean(ENABLED_KEY, false)
            .also { enabledCache = it }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        enabledCache = enabled
        com.saab.tv.data.account.AccountStorage.preferences(context, PREFERENCES)
            .edit()
            .putBoolean(ENABLED_KEY, enabled)
            .apply()
    }

    fun beginSession(
        context: Context,
        sessionId: String,
        title: String,
        player: String,
        mediaUrl: String
    ) {
        if (!isEnabled(context)) return
        val directory = sessionDirectory(context, sessionId)
        if (!directory.exists() && !directory.mkdirs()) return
        val safeTitle = sanitize(title).take(120).ifBlank { "Unknown Title" }
        val metadata = File(directory, METADATA_FILE)
        if (!metadata.exists()) {
            runCatching {
                metadata.writeText("${System.currentTimeMillis()}\t$safeTitle\n")
            }
        }
        event(
            context = context,
            sessionId = sessionId,
            component = "Playback",
            event = "Session Started",
            details = "player=${sanitize(player)} source=${describeUrl(mediaUrl)}"
        )
        trimOldSessions(context)
    }

    fun event(
        context: Context,
        sessionId: String,
        component: String,
        event: String,
        details: String = "",
        level: String = "INFO"
    ) {
        if (sessionId.isBlank() || !isEnabled(context)) return
        val directory = sessionDirectory(context, sessionId)
        if (!directory.exists() && !directory.mkdirs()) return
        val logFile = File(directory, "${processLabel()}.log")
        val line = buildString {
            append(System.currentTimeMillis())
            append('\t')
            append(sanitize(level).take(12))
            append('\t')
            append(sanitize(component).take(40))
            append('\t')
            append(sanitize(event).take(80))
            append('\t')
            append(sanitize(details).take(600))
            append('\n')
        }
        runCatching {
            if (logFile.length() >= MAX_LOG_BYTES) rotate(logFile)
            FileOutputStream(logFile, true).bufferedWriter().use { it.write(line) }
        }
    }

    fun memorySummary(): String {
        val runtime = Runtime.getRuntime()
        val javaUsedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)
        val javaMaxMb = runtime.maxMemory() / (1024L * 1024L)
        val pssMb = Debug.getPss() / 1024L
        return "pss=${pssMb}MB java=${javaUsedMb}MB/${javaMaxMb}MB"
    }

    fun describeUrl(rawUrl: String): String {
        val uri = runCatching { URI(rawUrl.trim()) }.getOrNull()
        val scheme = uri?.scheme?.lowercase(Locale.US).orEmpty()
        val host = uri?.host?.lowercase(Locale.US).orEmpty()
        return when {
            host.isNotBlank() -> "$scheme://$host"
            scheme.isNotBlank() -> "$scheme://redacted"
            else -> "redacted"
        }
    }

    fun latestReport(context: Context): PlaybackDiagnosticReport? {
        val directory = root(context).listFiles { file -> file.isDirectory }
            .orEmpty()
            .maxByOrNull { it.name.substringBefore('-').toLongOrNull() ?: it.lastModified() }
            ?: return null
        val metadata = File(directory, METADATA_FILE).takeIf(File::isFile)
            ?.readLines()
            ?.firstOrNull()
            .orEmpty()
            .split('\t', limit = 2)
        val startedAt = metadata.getOrNull(0)?.toLongOrNull()
            ?: directory.name.substringBefore('-').toLongOrNull()
            ?: directory.lastModified()
        val title = metadata.getOrNull(1).orEmpty().ifBlank { "Unknown Title" }
        val events = directory.listFiles { file -> file.isFile && file.extension == "log" }
            .orEmpty()
            .flatMap { file -> runCatching { file.readLines() }.getOrDefault(emptyList()) }
            .mapNotNull(::parseEvent)
            .sortedByDescending(PlaybackDiagnosticEvent::timestampMs)
            .take(MAX_PANEL_EVENTS)
        return PlaybackDiagnosticReport(directory.name, title, startedAt, events)
    }

    fun exportLatest(context: Context): File? {
        val report = latestReport(context) ?: return null
        val directory = File(context.cacheDir, EXPORT_DIRECTORY)
        if (!directory.exists() && !directory.mkdirs()) return null
        val output = File(directory, "saab-tv-playback-diagnostics.txt")
        return runCatching {
            output.bufferedWriter().use { writer ->
                writer.appendLine("Saab TV Playback Diagnostics")
                writer.appendLine("Session: ${report.sessionId}")
                writer.appendLine("Title: ${report.title}")
                writer.appendLine("Started: ${formatTimestamp(report.startedAtMs)}")
                writer.appendLine()
                val health = AppHealthMonitor.latestSummary(context)
                if (health.isNotEmpty()) {
                    writer.appendLine("Application Health")
                    health.forEach(writer::appendLine)
                    writer.appendLine()
                    writer.appendLine("Playback Events")
                }
                report.events.asReversed().forEach { item ->
                    writer.append(formatTimestamp(item.timestampMs))
                    writer.append("  ${item.level}  ${item.component}  ${item.event}")
                    if (item.details.isNotBlank()) writer.append("  ${item.details}")
                    writer.appendLine()
                }
            }
            output
        }.getOrNull()
    }

    fun clear(context: Context) {
        runCatching { root(context).deleteRecursively() }
        runCatching { File(context.cacheDir, EXPORT_DIRECTORY).deleteRecursively() }
    }

    fun formatTimestamp(timestampMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestampMs))

    private fun parseEvent(line: String): PlaybackDiagnosticEvent? {
        val values = line.split('\t', limit = 5)
        if (values.size < 4) return null
        return PlaybackDiagnosticEvent(
            timestampMs = values[0].toLongOrNull() ?: return null,
            level = values[1],
            component = values[2],
            event = values[3],
            details = values.getOrElse(4) { "" }
        )
    }

    private fun rotate(file: File) {
        val old = File(file.parentFile, "${file.nameWithoutExtension}.previous.log")
        old.delete()
        file.renameTo(old)
    }

    private fun trimOldSessions(context: Context) {
        root(context).listFiles { file -> file.isDirectory }
            .orEmpty()
            .sortedByDescending { it.name.substringBefore('-').toLongOrNull() ?: it.lastModified() }
            .drop(MAX_SESSIONS)
            .forEach { runCatching { it.deleteRecursively() } }
    }

    private fun root(context: Context): File = File(context.filesDir, DIRECTORY)

    private fun sessionDirectory(context: Context, sessionId: String): File =
        File(root(context), sanitize(sessionId).replace(' ', '_').take(80))

    private fun processLabel(): String {
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            "process-${Process.myPid()}"
        }
        return raw.substringAfterLast(':').replace(Regex("[^A-Za-z0-9_-]"), "_")
    }

    private fun sanitize(value: String): String = value
        .replace('\t', ' ')
        .replace('\r', ' ')
        .replace('\n', ' ')
        .replace(Regex("(?i)(token|apikey|api_key|authorization|password)=([^&\\s]+)"), "$1=redacted")
}
