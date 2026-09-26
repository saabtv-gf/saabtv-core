package com.saab.tv.data.account

/** Routine writes are coalesced; manual sync/sign-out can still flush immediately. */
internal class AccountSyncCadence(nowMs: Long) {
    private var dirty = true
    private var nextAutomaticSyncAt = nowMs + INTERVAL_MS

    @Synchronized fun markDirty() { dirty = true }

    @Synchronized fun beginAttempt(nowMs: Long, automatic: Boolean): Boolean {
        if (automatic && (!dirty || nowMs < nextAutomaticSyncAt)) return false
        dirty = false
        nextAutomaticSyncAt = nowMs + INTERVAL_MS
        return true
    }

    companion object { const val INTERVAL_MS = 60_000L }
}
