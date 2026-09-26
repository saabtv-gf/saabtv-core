package com.saab.tv.ui.player.base

import androidx.media3.common.C

internal object SubtitleTrackPreference {
    fun findPreferred(
        tracks: List<PlayerTrackOption>,
        preferredLanguage: String
    ): PlayerTrackOption? {
        val preferredKey = normalizeLanguageToIso2(preferredLanguage)
        if (preferredKey == "und") return null

        return tracks
            .asSequence()
            .filter { it.supported }
            .filter { normalizeLanguageToIso2(it.language) == preferredKey }
            .minWithOrNull(
                compareBy<PlayerTrackOption> { it.subtitleSourcePriority }
                    .thenBy {
                        (it.roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND) != 0
                    }
                    .thenBy { (it.selectionFlags and C.SELECTION_FLAG_FORCED) != 0 }
            )
    }
}
