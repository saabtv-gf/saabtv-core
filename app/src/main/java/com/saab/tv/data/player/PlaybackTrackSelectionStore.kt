package com.saab.tv.data.player

import android.content.Context
import com.saab.tv.data.account.AccountStorage
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

    private val prefs = AccountStorage.preferences(context, PREFS_FILE)

    fun getSelection(playbackId: String): Selection? {
        val scopedId = canonicalPlaybackId(playbackId) ?: return null
        val audioTrackId = readString(audioKey(scopedId), legacyAudioKey(scopedId))
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        // Older builds persisted whichever subtitle auto-selection happened to
        // win first (often OpenSubtitles). Only restore selections explicitly
        // made by the user in the player.
        val subtitleTrackId = if (readBoolean(
                subtitleExplicitKey(scopedId), legacySubtitleExplicitKey(scopedId), false
            )
        ) {
            readString(subtitleKey(scopedId), legacySubtitleKey(scopedId))
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        } else null
        val subtitleDelay = readLongOrNull(subtitleDelayKey(scopedId), legacySubtitleDelayKey(scopedId))
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
                    remove(legacyAudioKey(scopedId))
                } else {
                    putString(audioKey(scopedId), normalizedAudio)
                }
            }
            if (updateSubtitle) {
                val normalizedSubtitle = subtitleTrackId?.trim()?.takeIf { it.isNotEmpty() }
                if (normalizedSubtitle == null) {
                    remove(subtitleKey(scopedId))
                    remove(legacySubtitleKey(scopedId))
                    remove(subtitleExplicitKey(scopedId))
                    remove(legacySubtitleExplicitKey(scopedId))
                } else {
                    putString(subtitleKey(scopedId), normalizedSubtitle)
                    putBoolean(subtitleExplicitKey(scopedId), true)
                }
            }
            if (updateSubtitleDelay) {
                val delay = subtitleDelayMs
                if (delay == null || delay == 0L) {
                    remove(subtitleDelayKey(scopedId))
                    remove(legacySubtitleDelayKey(scopedId))
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
            .remove(legacyAudioKey(scopedId))
            .remove(subtitleKey(scopedId))
            .remove(legacySubtitleKey(scopedId))
            .remove(subtitleExplicitKey(scopedId))
            .remove(legacySubtitleExplicitKey(scopedId))
            .remove(subtitleDelayKey(scopedId))
            .remove(legacySubtitleDelayKey(scopedId))
            .apply()
    }

    fun clearSelectionsForPrefix(prefix: String) {
        val editor = prefs.edit()
        val profilePrefixes = profilePrefixes()
        prefs.all.keys.forEach { key ->
            if (profilePrefixes.any { profilePrefix ->
                    key.startsWith("${KEY_AUDIO_PREFIX}$profilePrefix$prefix") ||
                        key.startsWith("${KEY_SUBTITLE_PREFIX}$profilePrefix$prefix") ||
                        key.startsWith("${KEY_SUBTITLE_EXPLICIT_PREFIX}$profilePrefix$prefix") ||
                        key.startsWith("${KEY_SUBTITLE_DELAY_PREFIX}$profilePrefix$prefix")
                }
            ) {
                editor.remove(key)
            }
        }
        editor.apply()
    }

    private fun canonicalPlaybackId(playbackId: String): String? {
        return playbackId.trim().takeIf { it.isNotEmpty() }
    }

    private fun audioKey(scopedId: String): String = "${KEY_AUDIO_PREFIX}${activeProfilePrefix()}$scopedId"
    private fun legacyAudioKey(scopedId: String): String = "${KEY_AUDIO_PREFIX}${legacyProfilePrefix()}$scopedId"

    private fun subtitleKey(scopedId: String): String = "${KEY_SUBTITLE_PREFIX}${activeProfilePrefix()}$scopedId"
    private fun legacySubtitleKey(scopedId: String): String = "${KEY_SUBTITLE_PREFIX}${legacyProfilePrefix()}$scopedId"

    private fun subtitleExplicitKey(scopedId: String): String =
        "${KEY_SUBTITLE_EXPLICIT_PREFIX}${activeProfilePrefix()}$scopedId"
    private fun legacySubtitleExplicitKey(scopedId: String): String =
        "${KEY_SUBTITLE_EXPLICIT_PREFIX}${legacyProfilePrefix()}$scopedId"

    private fun subtitleDelayKey(scopedId: String): String = "${KEY_SUBTITLE_DELAY_PREFIX}${activeProfilePrefix()}$scopedId"
    private fun legacySubtitleDelayKey(scopedId: String): String =
        "${KEY_SUBTITLE_DELAY_PREFIX}${legacyProfilePrefix()}$scopedId"

    /** Use portable IDs for account sync: Room integer profile IDs are device-local. */
    private fun activeProfilePrefix(): String = profileConfigurationManager.getLastActivePortableProfileId()
        ?.let { "u${it}_" } ?: legacyProfilePrefix()

    /** Legacy namespace retained only for reading/migrating pre-portable-key selections. */
    private fun legacyProfilePrefix(): String = profileConfigurationManager.getLastActiveProfileId()
        ?.let { "p${it}_" } ?: "pnone_"

    private fun profilePrefixes(): Set<String> = setOf(activeProfilePrefix(), legacyProfilePrefix())

    private fun readString(currentKey: String, legacyKey: String): String? {
        prefs.getString(currentKey, null)?.let { return it }
        if (currentKey == legacyKey) return null
        val legacy = prefs.getString(legacyKey, null) ?: return null
        prefs.edit().putString(currentKey, legacy).remove(legacyKey).apply()
        return legacy
    }

    private fun readBoolean(currentKey: String, legacyKey: String, default: Boolean): Boolean {
        if (prefs.contains(currentKey)) return prefs.getBoolean(currentKey, default)
        if (currentKey == legacyKey || !prefs.contains(legacyKey)) return default
        val legacy = prefs.getBoolean(legacyKey, default)
        prefs.edit().putBoolean(currentKey, legacy).remove(legacyKey).apply()
        return legacy
    }

    private fun readLongOrNull(currentKey: String, legacyKey: String): Long? {
        if (prefs.contains(currentKey)) return prefs.getLong(currentKey, 0L)
        if (currentKey == legacyKey || !prefs.contains(legacyKey)) return null
        val legacy = prefs.getLong(legacyKey, 0L)
        prefs.edit().putLong(currentKey, legacy).remove(legacyKey).apply()
        return legacy
    }

    companion object {
        private const val PREFS_FILE = "playback_track_selection_prefs"
        private const val KEY_AUDIO_PREFIX = "audio_"
        private const val KEY_SUBTITLE_PREFIX = "subtitle_"
        private const val KEY_SUBTITLE_EXPLICIT_PREFIX = "subtitleExplicit_"
        private const val KEY_SUBTITLE_DELAY_PREFIX = "subtitleDelay_"
    }
}
