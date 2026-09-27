package com.saab.tv.data.account

/** Whole-account last-write-wins. Unknown times and ties prefer the cloud. */
internal fun localSnapshotIsNewer(localChangedAt: Long, cloudChangedAt: Long): Boolean =
    localChangedAt > 0 && cloudChangedAt > 0 && localChangedAt > cloudChangedAt
