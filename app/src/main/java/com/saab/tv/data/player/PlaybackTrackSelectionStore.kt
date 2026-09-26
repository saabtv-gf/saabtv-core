package com.saab.tv.data.player

import android.content.Context
import com.saab.tv.data.profile.ProfileConfigurationManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaybackTrackSelectionStore @Inject constructor(
    @ApplicationContext context: Context,
    private val profileConfigurationManager: ProfileConfigurationManager
) {
    data class Selection(
        val audioTrackId: String?,
        val subtitleTrackId: String?,
        val subtitleDelayMs: Long? = null
    )

    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    fun getSelection(playbackId: String): Selection? {
        val scopedId = canonicalPlaybackId(playbackId) ?: return null
        val audioTrackId = prefs.getString(audioKey(scopedId), null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        // Older builds persisted whichever subtitle auto-selection happened to
        // win first (often OpenSubtitles). Only restore selections explicitly
        // made by the user in the player.
        val subtitleTrackId = if (prefs.getBoolean(subtitleExplicitKey(scopedId), false)) {
            prefs.getString(subtitleKey(scopedId), null)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        } else null
        val subtitleDelay = if (prefs.contains(subtitleDelayKey(scopedId))) {
            prefs.getLong(subtitleDelayKey(scopedId), 0L)
        } else {
            null
        }
        if (audioTrackId == null && subtitleTrackId == null && subtitleDelay == null) return null
        return Selection(
            audioTrackId = audioTrackId,
            subtitleTrackId = subtitleTrackId,
            subtitleDelayMs = subtitleDelay
        )
    }

    fun updateSelection(
        playbackId: String,
        audioTrackId: String?,
        subtitleTrackId: String?,
        subtitleDelayMs: Long? = null,
        updateAudio: Boolean,
        updateSubtitle: Boolean,
        updateSubtitleDelay: Boolean = false
    ) {
        val scopedId = canonicalPlaybackId(playbackId) ?: return
        if (!updateAudio && !updateSubtitle && !updateSubtitleDelay) return

        prefs.edit().apply {
            if (updateAudio) {
                val normalizedAudio = audioTrackId?.trim()?.takeIf { it.isNotEmpty() }
                if (normalizedAudio == null) {
                    remove(audioKey(scopedId))
                } else {
                    putString(audioKey(scopedId), normalizedAudio)
                }
            }
            if (updateSubtitle) {
                val normalizedSubtitle = subtitleTrackId?.trim()?.takeIf { it.isNotEmpty() }
                if (normalizedSubtitle == null) {
                    remove(subtitleKey(scopedId))
                    remove(subtitleExplicitKey(scopedId))
                } else {
                    putString(subtitleKey(scopedId), normalizedSubtitle)
                    putBoolean(subtitleExplicitKey(scopedId), true)
                }
            }
            if (updateSubtitleDelay) {
                val delay = subtitleDelayMs
                if (delay == null || delay == 0L) {
                    remove(subtitleDelayKey(scopedId))
                } else {
                    putLong(subtitleDelayKey(scopedId), delay)
                }
            }
        }.apply()
    }

    fun clearSelection(playbackId: String) {
        val scopedId = canonicalPlaybackId(playbackId) ?: return
        prefs.edit()
            .remove(audioKey(scopedId))
            .remove(subtitleKey(scopedId))
            .remove(subtitleExplicitKey(scopedId))
            .remove(subtitleDelayKey(scopedId))
            .apply()
    }

    fun clearSelectionsForPrefix(prefix: String) {
        val editor = prefs.edit()
        val profilePrefix = "p${activeProfileId()}_"
        prefs.all.keys.forEach { key ->
            if (key.startsWith("${KEY_AUDIO_PREFIX}$profilePrefix$prefix") ||
                key.startsWith("${KEY_SUBTITLE_PREFIX}$profilePrefix$prefix") ||
                key.startsWith("${KEY_SUBTITLE_EXPLICIT_PREFIX}$profilePrefix$prefix") ||
                key.startsWith("${KEY_SUBTITLE_DELAY_PREFIX}$profilePrefix$prefix")
            ) {
                editor.remove(key)
            }
        }
        editor.apply()
    }

    private fun canonicalPlaybackId(playbackId: String): String? {
        return playbackId.trim().takeIf { it.isNotEmpty() }
    }

    private fun audioKey(scopedId: String): String = "${KEY_AUDIO_PREFIX}p${activeProfileId()}_$scopedId"

    private fun subtitleKey(scopedId: String): String = "${KEY_SUBTITLE_PREFIX}p${activeProfileId()}_$scopedId"

    private fun subtitleExplicitKey(scopedId: String): String =
        "${KEY_SUBTITLE_EXPLICIT_PREFIX}p${activeProfileId()}_$scopedId"

    private fun subtitleDelayKey(scopedId: String): String = "${KEY_SUBTITLE_DELAY_PREFIX}p${activeProfileId()}_$scopedId"

    private fun activeProfileId(): Int = profileConfigurationManager.getLastActiveProfileId() ?: 1

    companion object {
        private const val PREFS_FILE = "playback_track_selection_prefs"
        private const val KEY_AUDIO_PREFIX = "audio_"
        private const val KEY_SUBTITLE_PREFIX = "subtitle_"
        private const val KEY_SUBTITLE_EXPLICIT_PREFIX = "subtitleExplicit_"
        private const val KEY_SUBTITLE_DELAY_PREFIX = "subtitleDelay_"
    }
}
