package com.saab.tv.data.account

/** Timers exist only while dirty: batch progress, debounce edits, flush explicit boundaries. */
internal class AccountSyncCadence(nowMs: Long) {
    private var firstDirtyAt: Long? = null
    private var dueAt = nowMs
    private var notBefore = 0L
    @Synchronized fun markDirty(nowMs: Long, progressOnly: Boolean = false, urgent: Boolean = false) {
        val wasDirty = firstDirtyAt != null && dueAt != Long.MAX_VALUE
        val first = if (wasDirty) firstDirtyAt!! else nowMs.also { firstDirtyAt = it }
        val proposed = nowMs + when { urgent -> 500L; progressOnly -> 120_000L; else -> 2_000L }
        dueAt = maxOf(notBefore, if (!wasDirty) proposed else minOf(dueAt, proposed, first + 120_000L))
    }
    @Synchronized fun waitMs(nowMs: Long): Long? = firstDirtyAt?.takeIf { dueAt != Long.MAX_VALUE }?.let { (dueAt - nowMs).coerceAtLeast(0) }
    @Synchronized fun beginAttempt(nowMs: Long, automatic: Boolean): Boolean {
        if (isRateLimited(nowMs)) return false
        if (automatic && (firstDirtyAt == null || nowMs < dueAt)) return false
        firstDirtyAt = null
        return true
    }
    @Synchronized fun isRateLimited(nowMs: Long): Boolean = nowMs < notBefore
    @Synchronized fun retryAt(nowMs: Long, delayMs: Long) {
        firstDirtyAt = firstDirtyAt ?: nowMs
        dueAt = maxOf(notBefore, nowMs + delayMs)
    }
    /** New edits and manual retries cannot bypass the server's quota window. */
    @Synchronized fun rateLimited(nowMs: Long, delayMs: Long) {
        notBefore = maxOf(notBefore, nowMs + delayMs)
        retryAt(nowMs, delayMs)
    }
    @Synchronized fun deferUntilEvent(nowMs: Long) {
        firstDirtyAt = firstDirtyAt ?: nowMs
        dueAt = Long.MAX_VALUE
    }
}
