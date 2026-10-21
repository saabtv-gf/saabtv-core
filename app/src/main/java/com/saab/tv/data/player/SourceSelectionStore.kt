package com.saab.tv.data.player

import android.content.Context
import com.saab.tv.data.account.AccountStorage
import com.saab.tv.data.account.SourceSelectionStatePayload
import com.saab.tv.data.model.stremio.Stream
import com.saab.tv.data.profile.ProfileConfigurationManager
import com.saab.tv.data.repository.CanonicalIdentityStore
import com.saab.tv.data.repository.NoopCanonicalIdentityStore
import com.saab.tv.domain.schema.CanonicalEntityKey
import com.saab.tv.domain.schema.ContentIdentity
import com.saab.tv.domain.schema.IdentityNamespace
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SourceSelectionStore @Inject constructor(
    @ApplicationContext context: Context,
    private val profileConfigurationManager: ProfileConfigurationManager,
    private val canonicalIdentityStore: CanonicalIdentityStore
) {
    constructor(context: Context, profileConfigurationManager: ProfileConfigurationManager) :
        this(context, profileConfigurationManager, NoopCanonicalIdentityStore)

    private val prefs = AccountStorage.preferences(context, PREFS_FILE)

    fun rememberSelection(playbackId: String, stream: Stream, mediaType: String? = null) {
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
        portableSelection(playbackId, mediaType, stream)?.let { selection ->
            profileConfigurationManager.getLastActivePortableProfileId()?.let { portableProfileId ->
                storePortableSelection(portableProfileId, selection)
            }
        }
    }

    fun portableSelection(
        playbackId: String,
        mediaType: String?,
        stream: Stream
    ): SourceSelectionStatePayload? {
        val scopeId = canonicalSourceScopeId(playbackId)
        val type = mediaType?.lowercase(Locale.ROOT)
            ?.takeIf { it == "movie" || it == "series" }
            ?: if (scopeId != playbackId.trim()) "series" else "movie"
        val torrent = EpisodeStreamContinuity.torrentIdentity(stream)
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.matches(PORTABLE_TORRENT_IDENTITY) }
            ?: return null
        return SourceSelectionStatePayload(
            titleId = scopeId,
            type = type,
            torrentIdentity = torrent
        )
    }

    /** Applies a v3 value under the profile's portable ID, independent of this device's Room row ID. */
    fun applySyncedSelection(portableProfileId: String, localProfileId: Int, selection: SourceSelectionStatePayload) {
        require(portableProfileId.isNotBlank() && portableProfileId.length <= 128)
        validatePortableSelection(selection)
        clearLocalSelection(localProfileId, selection.titleId)
        storePortableSelection(portableProfileId, selection)
    }

    fun clearSyncedSelection(portableProfileId: String, localProfileId: Int, titleId: String, mediaType: String) {
        require(portableProfileId.isNotBlank() && portableProfileId.length <= 128)
        // Preserve type validation at the transport boundary even though both kinds share the same local key shape.
        require(mediaType == "movie" || mediaType == "series")
        val scopeId = canonicalSourceScopeId(titleId)
        prefs.edit().remove(portableKey(portableProfileId, scopeId, V3_TORRENT)).apply()
        clearLocalSelection(localProfileId, scopeId)
    }

    fun clearSelection(playbackId: String) {
        val scopedId = canonicalSourceScopeId(playbackId)
        val editor = prefs.edit()
            .remove(streamKey(scopedId))
            .remove(fileKey(scopedId))
            .remove(releaseKey(scopedId))
            .remove(torrentPrefKey(scopedId))
            .remove(addonPrefKey(scopedId))
            .remove(bingePrefKey(scopedId))
        profileConfigurationManager.getLastActivePortableProfileId()?.let { portableId ->
            editor.remove(portableKey(portableId, scopedId, V3_TORRENT))
        }
        editor.apply()
    }

    fun clearSelectionsForPrefix(prefix: String) {
        val editor = prefs.edit()
        val profilePrefix = activeProfilePrefix()
        val portablePrefix = profileConfigurationManager.getLastActivePortableProfileId()
            ?.let { "v3_source_${it}_$prefix" }
        prefs.all.keys.forEach { key ->
            if (key.startsWith("file_$profilePrefix$prefix") ||
                key.startsWith("release_$profilePrefix$prefix") ||
                key.startsWith("${KEY_STREAM_PREFIX}$profilePrefix$prefix") ||
                key.startsWith("${KEY_TORRENT_PREFIX}$profilePrefix$prefix") ||
                key.startsWith("${KEY_ADDON_PREFIX}$profilePrefix$prefix")
                || key.startsWith("${KEY_BINGE_PREFIX}$profilePrefix$prefix") ||
                (!portablePrefix.isNullOrBlank() && key.startsWith(portablePrefix))
            ) {
                editor.remove(key)
            }
        }
        editor.apply()
    }

    fun hasRememberedSelection(playbackId: String): Boolean {
        return hasRememberedSelectionInScopes(preferenceScopes(playbackId))
    }

    /** Alias-aware variant for a known movie/series kind; opaque provider IDs remain exact. */
    suspend fun hasRememberedSelection(playbackId: String, mediaType: String): Boolean =
        hasRememberedSelectionInScopes(verifiedPreferenceScopes(playbackId, mediaType))

    private fun hasRememberedSelectionInScopes(scopes: List<String>): Boolean {
        val local = scopes.any { scopedId ->
            !prefs.getString(streamKey(scopedId), null).isNullOrBlank() ||
                !prefs.getString(torrentPrefKey(scopedId), null).isNullOrBlank() ||
                !prefs.getString(addonPrefKey(scopedId), null).isNullOrBlank() ||
                !prefs.getString(bingePrefKey(scopedId), null).isNullOrBlank()
        }
        if (local) return true
        val portableId = profileConfigurationManager.getLastActivePortableProfileId() ?: return false
        return scopes.any { scopedId ->
            !prefs.getString(portableKey(portableId, scopedId, V3_TORRENT), null).isNullOrBlank()
        }
    }

    fun findPreferredStream(playbackId: String, streams: List<Stream>): Stream? {
        return findPreferredStreamInScopes(playbackId, preferenceScopes(playbackId), streams)
    }

    /**
     * Resolves remembered choices through verified IMDb/TMDB aliases. Provider IDs are still
     * read as exact IDs when no verified identity link exists; we never guess addon equivalence.
     */
    suspend fun findPreferredStream(playbackId: String, mediaType: String, streams: List<Stream>): Stream? {
        return findPreferredStreamInScopes(playbackId, verifiedPreferenceScopes(playbackId, mediaType), streams)
    }

    private fun findPreferredStreamInScopes(
        playbackId: String,
        scopes: List<String>,
        streams: List<Stream>
    ): Stream? {
        if (streams.isEmpty()) return null

        val playableStreams = streams.filter(::isPlayableStream)
        if (playableStreams.isEmpty()) return null

        scopes.forEach { scopedId ->
            val portableId = profileConfigurationManager.getLastActivePortableProfileId()
            val portableTorrent = portableId?.let {
                prefs.getString(portableKey(it, scopedId, V3_TORRENT), null)
            }
            val savedFingerprint = prefs.getString(streamKey(scopedId), null)
            val savedTorrent = portableTorrent
                ?: prefs.getString(torrentPrefKey(scopedId), null)
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
                    EpisodeStreamContinuity.torrentIdentity(it)?.equals(savedTorrent, ignoreCase = true) == true
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

    private suspend fun verifiedPreferenceScopes(playbackId: String, mediaType: String): List<String> {
        val exactScopes = preferenceScopes(playbackId)
        if (mediaType != "movie" && mediaType != "series") return exactScopes
        val titleId = canonicalSourceScopeId(playbackId)
        val identity = CanonicalEntityKey.titleIdentity(titleId, mediaType) ?: return exactScopes
        val verified = canonicalIdentityStore.verifiedIdentities(identity)
            .asSequence()
            .filter { it.kind == identity.kind }
            .filter { it.namespace == IdentityNamespace.IMDB || it.namespace == IdentityNamespace.TMDB }
            .map(::sourceScopeForIdentity)
            .toList()
        return (exactScopes + verified).distinct()
    }

    private fun sourceScopeForIdentity(identity: ContentIdentity): String = when (identity.namespace) {
        IdentityNamespace.IMDB -> identity.externalId
        IdentityNamespace.TMDB -> "tmdb:${identity.externalId}"
        IdentityNamespace.ADDON -> identity.externalId
    }

    private fun streamKey(scopedId: String): String = "${KEY_STREAM_PREFIX}${activeProfilePrefix()}$scopedId"
    private fun fileKey(scopedId: String): String = "file_${activeProfilePrefix()}$scopedId"
    private fun releaseKey(scopedId: String): String = "release_${activeProfilePrefix()}$scopedId"

    private fun torrentPrefKey(scopedId: String): String = "${KEY_TORRENT_PREFIX}${activeProfilePrefix()}$scopedId"

    private fun addonPrefKey(scopedId: String): String = "${KEY_ADDON_PREFIX}${activeProfilePrefix()}$scopedId"

    private fun bingePrefKey(scopedId: String): String = "${KEY_BINGE_PREFIX}${activeProfilePrefix()}$scopedId"

    /** No active profile gets an isolated namespace; it must never read/write profile 1's data. */
    private fun activeProfilePrefix(): String = profileConfigurationManager.getLastActiveProfileId()
        ?.let { "p${it}_" } ?: "pnone_"

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

    private fun storePortableSelection(portableProfileId: String, selection: SourceSelectionStatePayload) {
        prefs.edit()
            .putString(portableKey(portableProfileId, selection.titleId, V3_TORRENT), selection.torrentIdentity)
            .apply()
    }

    private fun clearLocalSelection(profileId: Int, titleId: String) {
        val scopedId = canonicalSourceScopeId(titleId)
        val prefix = "p${profileId}_"
        prefs.edit()
            .remove("${KEY_STREAM_PREFIX}$prefix$scopedId")
            .remove("file_${prefix}$scopedId")
            .remove("release_${prefix}$scopedId")
            .remove("${KEY_TORRENT_PREFIX}$prefix$scopedId")
            .remove("${KEY_ADDON_PREFIX}$prefix$scopedId")
            .remove("${KEY_BINGE_PREFIX}$prefix$scopedId")
            .apply()
    }

    private fun validatePortableSelection(selection: SourceSelectionStatePayload) {
        require(selection.titleId.isNotBlank() && selection.titleId.length <= 512)
        require(selection.type == "movie" || selection.type == "series")
        require(selection.torrentIdentity.matches(PORTABLE_TORRENT_IDENTITY))
        require(!selection.titleId.contains("://"))
    }

    private fun portableKey(portableProfileId: String, scopedId: String, field: String) =
        "v3_source_${portableProfileId}_${scopedId}_$field"

    companion object {
        private const val PREFS_FILE = "source_selection_prefs"
        private const val KEY_STREAM_PREFIX = "stream_"
        private const val KEY_TORRENT_PREFIX = "torrent_"
        private const val KEY_ADDON_PREFIX = "addon_"
        private const val KEY_BINGE_PREFIX = "binge_"
        private const val V3_TORRENT = "torrent"
        private val PORTABLE_TORRENT_IDENTITY = Regex("(?:[a-f0-9]{40}|[a-z2-7]{32})", RegexOption.IGNORE_CASE)

        fun clearPortableProfile(context: Context, portableProfileId: String) {
            val prefs = AccountStorage.preferences(context, PREFS_FILE)
            prefs.edit().also { editor ->
                prefs.all.keys.filter { it.startsWith("v3_source_${portableProfileId}_") }
                    .forEach(editor::remove)
            }.apply()
        }
    }
}
