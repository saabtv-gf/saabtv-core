package com.saab.tv.ui.player.base

/** A completed manual choice remains in force until the user changes it. */
internal object SubtitleSelectionPolicy {
    fun preserveOffOnSourceChange(manualSelection: Boolean, selectedId: String?): Boolean =
        manualSelection && selectedId == "#none"

    fun retainManualSelection(
        wasManual: Boolean,
        pendingId: String?,
        resolvedPendingId: String?
    ): Boolean = wasManual && (pendingId == null || resolvedPendingId != null)

    fun shouldUpgradeToEmbedded(
        manualSelection: Boolean,
        pendingId: String?,
        currentId: String?,
        preferred: PlayerTrackOption?
    ): Boolean = !manualSelection &&
        pendingId != "#none" &&
        preferred?.subtitleSourcePriority == SubtitleSourcePriority.EMBEDDED &&
        preferred.id != currentId
}
