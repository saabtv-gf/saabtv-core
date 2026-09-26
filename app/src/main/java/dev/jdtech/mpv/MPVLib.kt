/*
 * Java 17 source build of the MIT-licensed libmpv-android v1.0.0 wrapper.
 * Upstream: https://github.com/jarnedemeulemeester/libmpv-android/blob/v1.0.0/libmpv/src/main/java/dev/jdtech/mpv/MPVLib.kt
 */
package dev.jdtech.mpv

import android.content.Context
import android.view.Surface

@Suppress("unused")
class MPVLib private constructor(@Suppress("UNUSED_PARAMETER") nativePtr: Long) {
    private var nativeInstance: Long = 0
    private val observers = mutableListOf<EventObserver>()
    private val logObservers = mutableListOf<LogObserver>()

    companion object {
        init {
            arrayOf("mpv", "player", "mpvdiag").forEach(System::loadLibrary)
        }

        @JvmStatic
        fun create(context: Context): MPVLib? {
            val instance = MPVLib(0L)
            val pointer = instance.nativeCreate(instance, context.applicationContext)
            if (pointer == 0L) return null
            instance.nativeInstance = pointer
            return instance
        }
    }

    private fun checkCreated() {
        check(nativeInstance != 0L) { "MPVLib is not initialized" }
    }

    private external fun nativeCreate(thiz: MPVLib, appctx: Context): Long

    fun init() {
        checkCreated()
        nativeInit(nativeInstance)
    }
    private external fun nativeInit(instance: Long)

    /**
     * Initializes the same native handle as [init], but preserves mpv's exact
     * return code and queued startup logs. The thumbnail worker does not need
     * the wrapper event thread because it polls properties while opening.
     *
     * @return null on success, otherwise a diagnostic error description.
     */
    fun initDetailed(): String? {
        checkCreated()
        return nativeInitDetailed(nativeInstance)
    }
    private external fun nativeInitDetailed(instance: Long): String?

    fun drainDiagnosticLogs(): String {
        checkCreated()
        return nativeDrainDiagnosticLogs(nativeInstance)
    }
    private external fun nativeDrainDiagnosticLogs(instance: Long): String

    /** BGRA frame prefixed with little-endian width, height and row stride. */
    fun screenshotRaw(): ByteArray? {
        checkCreated()
        return nativeScreenshotRaw(nativeInstance)
    }
    private external fun nativeScreenshotRaw(instance: Long): ByteArray?

    fun screenshotRawError(): String {
        checkCreated()
        return nativeScreenshotRawError(nativeInstance)
    }
    private external fun nativeScreenshotRawError(instance: Long): String

    fun destroy() {
        checkCreated()
        nativeDestroy(nativeInstance)
        nativeInstance = 0
    }
    private external fun nativeDestroy(instance: Long)

    fun attachSurface(surface: Surface) {
        checkCreated()
        nativeAttachSurface(nativeInstance, surface)
    }
    private external fun nativeAttachSurface(instance: Long, surface: Surface)

    fun detachSurface() {
        checkCreated()
        nativeDetachSurface(nativeInstance)
    }
    private external fun nativeDetachSurface(instance: Long)

    fun command(cmd: Array<String>) {
        checkCreated()
        nativeCommand(nativeInstance, cmd)
    }
    private external fun nativeCommand(instance: Long, cmd: Array<String>)

    fun commandDetailed(cmd: Array<String>): Int {
        checkCreated()
        return nativeCommandDetailed(nativeInstance, cmd)
    }
    private external fun nativeCommandDetailed(instance: Long, cmd: Array<String>): Int

    fun setOptionString(name: String, value: String): Int {
        checkCreated()
        return nativeSetOptionString(nativeInstance, name, value)
    }
    private external fun nativeSetOptionString(instance: Long, name: String, value: String): Int

    fun getPropertyInt(property: String): Int? {
        checkCreated()
        return nativeGetPropertyInt(nativeInstance, property)
    }
    private external fun nativeGetPropertyInt(instance: Long, property: String): Int?

    fun setPropertyInt(property: String, value: Int) {
        checkCreated()
        nativeSetPropertyInt(nativeInstance, property, value)
    }
    private external fun nativeSetPropertyInt(instance: Long, property: String, value: Int)

    fun getPropertyDouble(property: String): Double? {
        checkCreated()
        return nativeGetPropertyDouble(nativeInstance, property)
    }
    private external fun nativeGetPropertyDouble(instance: Long, property: String): Double?

    fun setPropertyDouble(property: String, value: Double) {
        checkCreated()
        nativeSetPropertyDouble(nativeInstance, property, value)
    }
    private external fun nativeSetPropertyDouble(instance: Long, property: String, value: Double)

    fun getPropertyBoolean(property: String): Boolean? {
        checkCreated()
        return nativeGetPropertyBoolean(nativeInstance, property)
    }
    private external fun nativeGetPropertyBoolean(instance: Long, property: String): Boolean?

    fun setPropertyBoolean(property: String, value: Boolean) {
        checkCreated()
        nativeSetPropertyBoolean(nativeInstance, property, value)
    }
    private external fun nativeSetPropertyBoolean(instance: Long, property: String, value: Boolean)

    fun getPropertyString(property: String): String? {
        checkCreated()
        return nativeGetPropertyString(nativeInstance, property)
    }
    private external fun nativeGetPropertyString(instance: Long, property: String): String?

    fun setPropertyString(property: String, value: String) {
        checkCreated()
        nativeSetPropertyString(nativeInstance, property, value)
    }
    private external fun nativeSetPropertyString(instance: Long, property: String, value: String)

    fun observeProperty(property: String, format: Int) {
        checkCreated()
        nativeObserveProperty(nativeInstance, property, format)
    }
    private external fun nativeObserveProperty(instance: Long, property: String, format: Int)

    fun addObserver(observer: EventObserver) = synchronized(observers) {
        observers.add(observer)
        Unit
    }

    fun removeObserver(observer: EventObserver) = synchronized(observers) {
        observers.remove(observer)
        Unit
    }

    fun eventProperty(property: String, value: Long) = synchronized(observers) {
        observers.forEach { it.eventProperty(property, value) }
    }

    fun eventProperty(property: String, value: Double) = synchronized(observers) {
        observers.forEach { it.eventProperty(property, value) }
    }

    fun eventProperty(property: String, value: Boolean) = synchronized(observers) {
        observers.forEach { it.eventProperty(property, value) }
    }

    fun eventProperty(property: String, value: String) = synchronized(observers) {
        observers.forEach { it.eventProperty(property, value) }
    }

    fun eventProperty(property: String) = synchronized(observers) {
        observers.forEach { it.eventProperty(property) }
    }

    fun event(eventId: Int) = synchronized(observers) {
        observers.forEach { it.event(eventId) }
    }

    fun addLogObserver(observer: LogObserver) = synchronized(logObservers) {
        logObservers.add(observer)
        Unit
    }

    fun removeLogObserver(observer: LogObserver) = synchronized(logObservers) {
        logObservers.remove(observer)
        Unit
    }

    fun logMessage(prefix: String, level: Int, text: String) = synchronized(logObservers) {
        logObservers.forEach { it.logMessage(prefix, level, text) }
    }

    interface EventObserver {
        fun eventProperty(property: String)
        fun eventProperty(property: String, value: Long)
        fun eventProperty(property: String, value: Double)
        fun eventProperty(property: String, value: Boolean)
        fun eventProperty(property: String, value: String)
        fun event(eventId: Int)
    }

    interface LogObserver {
        fun logMessage(prefix: String, level: Int, text: String)
    }

    object MpvFormat {
        const val MPV_FORMAT_NONE = 0
        const val MPV_FORMAT_STRING = 1
        const val MPV_FORMAT_OSD_STRING = 2
        const val MPV_FORMAT_FLAG = 3
        const val MPV_FORMAT_INT64 = 4
        const val MPV_FORMAT_DOUBLE = 5
        const val MPV_FORMAT_NODE = 6
        const val MPV_FORMAT_NODE_ARRAY = 7
        const val MPV_FORMAT_NODE_MAP = 8
        const val MPV_FORMAT_BYTE_ARRAY = 9
    }

    object MpvEvent {
        const val MPV_EVENT_NONE = 0
        const val MPV_EVENT_SHUTDOWN = 1
        const val MPV_EVENT_LOG_MESSAGE = 2
        const val MPV_EVENT_GET_PROPERTY_REPLY = 3
        const val MPV_EVENT_SET_PROPERTY_REPLY = 4
        const val MPV_EVENT_COMMAND_REPLY = 5
        const val MPV_EVENT_START_FILE = 6
        const val MPV_EVENT_END_FILE = 7
        const val MPV_EVENT_FILE_LOADED = 8
        const val MPV_EVENT_IDLE = 11
        const val MPV_EVENT_TICK = 14
        const val MPV_EVENT_CLIENT_MESSAGE = 16
        const val MPV_EVENT_VIDEO_RECONFIG = 17
        const val MPV_EVENT_AUDIO_RECONFIG = 18
        const val MPV_EVENT_SEEK = 20
        const val MPV_EVENT_PLAYBACK_RESTART = 21
        const val MPV_EVENT_PROPERTY_CHANGE = 22
        const val MPV_EVENT_QUEUE_OVERFLOW = 24
        const val MPV_EVENT_HOOK = 25
    }

    object MpvLogLevel {
        const val MPV_LOG_LEVEL_NONE = 0
        const val MPV_LOG_LEVEL_FATAL = 10
        const val MPV_LOG_LEVEL_ERROR = 20
        const val MPV_LOG_LEVEL_WARN = 30
        const val MPV_LOG_LEVEL_INFO = 40
        const val MPV_LOG_LEVEL_V = 50
        const val MPV_LOG_LEVEL_DEBUG = 60
        const val MPV_LOG_LEVEL_TRACE = 70
    }
}
