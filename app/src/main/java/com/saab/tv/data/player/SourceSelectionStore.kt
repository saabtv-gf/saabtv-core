package com.saab.tv.data.player

import android.content.Context
import com.saab.tv.data.model.stremio.Stream
import com.saab.tv.data.profile.ProfileConfigurationManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SourceSelectionStore @Inject constructor(
    @ApplicationContext context: Context,
    private val profileConfigurationManager: ProfileConfigurationManager
) {
    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    fun rememberSelection(playbackId: String, stream: Stream) {
        val scopedId = canonicalSourceScopeId(playbackId)
        val streamFingerprint = streamFingerprint(stream) ?: return
        val addonTag = addonTag(stream)
        val bingeGroup = normalize(stream.behaviorHints?.bingeGroup)
        val torrentIdentity = EpisodeStreamContinuity.torrentIdentity(stream)

        prefs.edit().apply {
            putString(streamKey(scopedId), streamFingerprint)
            putString(fileKey(scopedId), StreamIdentity.file(stream))
            putString(releaseKey(scopedId), StreamIdentity.episodeRelease(stream))
            if (torrentIdentity.isNullOrBlank()) {
                remove(torrentPrefKey(scopedId))
            } else {
                putString(torrentPrefKey(scopedId), torrentIdentity)
            }
            if (addonTag.isNullOrBlank()) {
                remove(addonPrefKey(scopedId))
            } else {
                putString(addonPrefKey(scopedId), addonTag)
            }
            if (bingeGroup.isNullOrBlank()) {
                remove(bingePrefKey(scopedId))
            } else {
                putString(bingePrefKey(scopedId), bingeGroup)
            }
        }.apply()
    }

    fun clearSelection(playbackId: String) {
        val scopedId = canonicalSourceScopeId(playbackId)
        prefs.edit()
            .remove(streamKey(scopedId))
            .remove(fileKey(scopedId))
            .remove(releaseKey(scopedId))
            .remove(torrentPrefKey(scopedId))
            .remove(addonPrefKey(scopedId))
            .remove(bingePrefKey(scopedId))
            .apply()
    }

    fun clearSelectionsForPrefix(prefix: String) {
        val editor = prefs.edit()
        val profilePrefix = "p${activeProfileId()}_"
        prefs.all.keys.forEach { key ->
            if (key.startsWith("file_$profilePrefix$prefix") ||
                key.startsWith("release_$profilePrefix$prefix") ||
                key.startsWith("${KEY_STREAM_PREFIX}$profilePrefix$prefix") ||
                key.startsWith("${KEY_TORRENT_PREFIX}$profilePrefix$prefix") ||
                key.startsWith("${KEY_ADDON_PREFIX}$profilePrefix$prefix")
                || key.startsWith("${KEY_BINGE_PREFIX}$profilePrefix$prefix")
            ) {
                editor.remove(key)
            }
        }
        editor.apply()
    }

    fun hasRememberedSelection(playbackId: String): Boolean {
        return preferenceScopes(playbackId).any { scopedId ->
            !prefs.getString(streamKey(scopedId), null).isNullOrBlank() ||
                !prefs.getString(torrentPrefKey(scopedId), null).isNullOrBlank() ||
                !prefs.getString(addonPrefKey(scopedId), null).isNullOrBlank() ||
                !prefs.getString(bingePrefKey(scopedId), null).isNullOrBlank()
        }
    }

    fun findPreferredStream(playbackId: String, streams: List<Stream>): Stream? {
        if (streams.isEmpty()) return null

        val playableStreams = streams.filter(::isPlayableStream)
        if (playableStreams.isEmpty()) return null

        preferenceScopes(playbackId).forEach { scopedId ->
            val savedFingerprint = prefs.getString(streamKey(scopedId), null)
            val savedTorrent = prefs.getString(torrentPrefKey(scopedId), null)
                ?: savedFingerprint?.substringBefore('|')?.takeIf { it.isNotBlank() }
            val savedAddon = prefs.getString(addonPrefKey(scopedId), null)
            val savedBingeGroup = prefs.getString(bingePrefKey(scopedId), null)

            if (!savedFingerprint.isNullOrBlank()) {
                val exactMatch = playableStreams.firstOrNull { streamFingerprint(it) == savedFingerprint }
                if (exactMatch != null) {
                    migrateLegacySelection(playbackId, scopedId, exactMatch)
                    return exactMatch
                }
                // Seeder counts and display labels can change between requests
                // even when the actual stream URL is identical.
                val savedUrl = savedFingerprint.split('|').getOrNull(1)?.takeIf { it.isNotBlank() }
                if (savedUrl != null) {
                    playableStreams.firstOrNull { normalize(it.url) == savedUrl }?.let { return it }
                }
            }

            if (!savedTorrent.isNullOrBlank()) {
                val torrentMatch = playableStreams.firstOrNull {
                    EpisodeStreamContinuity.torrentIdentity(it) == savedTorrent
                }
                if (torrentMatch != null) {
                    migrateLegacySelection(playbackId, scopedId, torrentMatch)
                    return torrentMatch
                }
            }

            val savedFile = prefs.getString(fileKey(scopedId), null)
            if (savedFile != null) {
                playableStreams.firstOrNull {
                    StreamIdentity.file(it) == savedFile &&
                        (savedAddon == null || addonTag(it) == savedAddon)
                }?.let { return it }
            }

            if (!savedBingeGroup.isNullOrBlank()) {
                val bingeMatch = playableStreams.firstOrNull { stream ->
                    normalize(stream.behaviorHints?.bingeGroup) == savedBingeGroup &&
                        (savedAddon.isNullOrBlank() || addonTag(stream) == savedAddon)
                }
                if (bingeMatch != null) {
                    migrateLegacySelection(playbackId, scopedId, bingeMatch)
                    return bingeMatch
                }
            }

            val savedRelease = prefs.getString(releaseKey(scopedId), null)
            if (savedRelease != null) {
                playableStreams.firstOrNull {
                    StreamIdentity.episodeRelease(it) == savedRelease &&
                        (savedAddon == null || addonTag(it) == savedAddon)
                }?.let { return it }
            }

            if (!savedAddon.isNullOrBlank()) {
                val addonMatch = playableStreams.firstOrNull { addonTag(it) == savedAddon }
                if (addonMatch != null) {
                    migrateLegacySelection(playbackId, scopedId, addonMatch)
                    return addonMatch
                }
            }
        }

        return null
    }

    private fun streamKey(scopedId: String): String = "${KEY_STREAM_PREFIX}p${activeProfileId()}_$scopedId"
    private fun fileKey(scopedId: String): String = "file_p${activeProfileId()}_$scopedId"
    private fun releaseKey(scopedId: String): String = "release_p${activeProfileId()}_$scopedId"

    private fun torrentPrefKey(scopedId: String): String = "${KEY_TORRENT_PREFIX}p${activeProfileId()}_$scopedId"

    private fun addonPrefKey(scopedId: String): String = "${KEY_ADDON_PREFIX}p${activeProfileId()}_$scopedId"

    private fun bingePrefKey(scopedId: String): String = "${KEY_BINGE_PREFIX}p${activeProfileId()}_$scopedId"

    private fun activeProfileId(): Int = profileConfigurationManager.getLastActiveProfileId() ?: 1

    private fun streamFingerprint(stream: Stream): String? {
        val normalizedInfoHash = normalize(stream.infoHash)
        val normalizedUrl = normalize(stream.url)
        val normalizedFileIdx = stream.fileIdx?.toString()
        val normalizedName = normalize(stream.name)
        val normalizedTitle = normalize(stream.title)

        if (
            normalizedInfoHash == null &&
            normalizedUrl == null &&
            normalizedFileIdx == null &&
            normalizedName == null &&
            normalizedTitle == null
        ) {
            return null
        }

        return listOf(
            normalizedInfoHash ?: "",
            normalizedUrl ?: "",
            normalizedFileIdx ?: "",
            normalizedName ?: "",
            normalizedTitle ?: ""
        ).joinToString("|")
    }

    private fun addonTag(stream: Stream): String? {
        normalize(stream.addonTransportUrl)?.let { return "transport:$it" }
        val sourceName = stream.name ?: return null
        if (!sourceName.contains("[") || !sourceName.contains("]")) return null
        return normalize(sourceName.substringAfter("[").substringBefore("]"))?.let { "name:$it" }
    }

    private fun canonicalSourceScopeId(playbackId: String): String {
        val trimmed = playbackId.trim()
        val parts = trimmed.split(":")
        if (parts.size < 3) return trimmed
        val hasEpisodeSuffix = parts[parts.lastIndex - 1].toIntOrNull() != null &&
            parts.last().toIntOrNull() != null
        return if (hasEpisodeSuffix) parts.dropLast(2).joinToString(":") else trimmed
    }

    private fun preferenceScopes(playbackId: String): List<String> {
        val exact = playbackId.trim()
        val canonical = canonicalSourceScopeId(exact)
        return listOf(canonical, exact).filter { it.isNotEmpty() }.distinct()
    }

    private fun migrateLegacySelection(playbackId: String, matchedScope: String, stream: Stream) {
        if (matchedScope != canonicalSourceScopeId(playbackId)) {
            rememberSelection(playbackId, stream)
        }
    }

    private fun normalize(value: String?): String? {
        if (value == null) return null
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        return trimmed.lowercase(Locale.ROOT)
    }

    private fun isPlayableStream(stream: Stream): Boolean {
        return !stream.url.isNullOrBlank() || !stream.infoHash.isNullOrBlank()
    }

    companion object {
        private const val PREFS_FILE = "source_selection_prefs"
        private const val KEY_STREAM_PREFIX = "stream_"
        private const val KEY_TORRENT_PREFIX = "torrent_"
        private const val KEY_ADDON_PREFIX = "addon_"
        private const val KEY_BINGE_PREFIX = "binge_"
    }
}
