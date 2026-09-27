package com.saab.tv.data.account

/** Timers exist only while dirty: batch progress, debounce edits, flush explicit boundaries. */
internal class AccountSyncCadence(nowMs: Long) {
    private var firstDirtyAt: Long? = null
    private var dueAt = nowMs
    @Synchronized fun markDirty(nowMs: Long, progressOnly: Boolean = false, urgent: Boolean = false) {
        val wasDirty = firstDirtyAt != null && dueAt != Long.MAX_VALUE
        val first = if (wasDirty) firstDirtyAt!! else nowMs.also { firstDirtyAt = it }
        val proposed = nowMs + when { urgent -> 500L; progressOnly -> 120_000L; else -> 2_000L }
        dueAt = if (!wasDirty) proposed else minOf(dueAt, proposed, first + 120_000L)
    }
    @Synchronized fun waitMs(nowMs: Long): Long? = firstDirtyAt?.takeIf { dueAt != Long.MAX_VALUE }?.let { (dueAt - nowMs).coerceAtLeast(0) }
    @Synchronized fun beginAttempt(nowMs: Long, automatic: Boolean): Boolean {
        if (automatic && (firstDirtyAt == null || nowMs < dueAt)) return false
        firstDirtyAt = null
        return true
    }
    @Synchronized fun retryAt(nowMs: Long, delayMs: Long) {
        firstDirtyAt = firstDirtyAt ?: nowMs
        dueAt = nowMs + delayMs
    }
    @Synchronized fun deferUntilEvent(nowMs: Long) {
        firstDirtyAt = firstDirtyAt ?: nowMs
        dueAt = Long.MAX_VALUE
    }
}
