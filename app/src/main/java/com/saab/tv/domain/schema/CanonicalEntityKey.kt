package com.saab.tv.domain.schema

import java.net.URLEncoder
import java.net.URI
import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Stable semantic key for user state, separate from local row IDs and display strings. */
object CanonicalEntityKey {
    fun profile(portableProfileId: String): String = "profile/${encode(portableProfileId.trim())}"
    fun profileRuntime(portableProfileId: String): String = "profile-runtime/${encode(portableProfileId.trim())}"

    fun title(id: String, type: String, addonInstallationId: String? = null): String {
        val kind = ContentKind.fromProvider(type) ?: return legacy(type, id)
        val identity = titleIdentity(id, type, addonInstallationId)
        return identity?.stableKey ?: legacy(kind.keyPart, id.trim())
    }

    /** Exact episode identity keeps the provider's opaque ID while anchoring it to its series. */
    fun playable(
        id: String,
        type: String,
        addonInstallationId: String? = null,
        canonicalSeriesIdentityKey: String? = null
    ): String {
        val raw = id.trim()
        val imdbEpisode = Regex("^(tt[0-9]+):([1-9][0-9]*:[1-9][0-9]*(?::[0-9]+)?)$").matchEntire(raw)
        if (imdbEpisode != null) {
            val seriesKey = canonicalSeriesIdentityKey
                ?: ContentIdentity.imdb(ContentKind.SERIES, imdbEpisode.groupValues[1])?.stableKey
            if (seriesKey != null) return "episode/$seriesKey/${encode(imdbEpisode.groupValues[2])}"
        }
        val tmdbEpisode = Regex("^tmdb:([1-9][0-9]*):([1-9][0-9]*:[1-9][0-9]*(?::[0-9]+)?)$", RegexOption.IGNORE_CASE)
            .matchEntire(raw)
        if (tmdbEpisode != null) {
            val seriesKey = canonicalSeriesIdentityKey
                ?: ContentIdentity.tmdb(ContentKind.SERIES, tmdbEpisode.groupValues[1])?.stableKey
            if (seriesKey != null) return "episode/$seriesKey/${encode(tmdbEpisode.groupValues[2])}"
        }
        return canonicalSeriesIdentityKey ?: title(raw, type, addonInstallationId)
    }

    /** Provider-qualified identity for a title or series root embedded in an episode ID. */
    fun titleIdentity(id: String, type: String, addonInstallationId: String? = null): ContentIdentity? {
        val kind = ContentKind.fromProvider(type) ?: return null
        val raw = id.trim()
        val seriesRoot = Regex("^(tt[0-9]+):.+$").matchEntire(raw)?.groupValues?.get(1)
        return when {
            kind == ContentKind.SERIES && seriesRoot != null -> ContentIdentity.imdb(kind, seriesRoot)
            raw.startsWith("tmdb:", ignoreCase = true) ->
                ContentIdentity.tmdb(kind, raw.substringAfter(':').substringBefore(':'))
            raw.matches(Regex("tt[0-9]+")) -> ContentIdentity.imdb(kind, raw)
            !addonInstallationId.isNullOrBlank() -> ContentIdentity.addon(addonInstallationId, kind, raw)
            else -> null
        }
    }

    /** Series identity corresponding to a playable episode; null for non-series/unknown IDs. */
    fun playableSeriesIdentity(
        id: String,
        type: String,
        addonInstallationId: String? = null
    ): ContentIdentity? {
        if (ContentKind.fromProvider(type) != ContentKind.SERIES) return null
        val raw = id.trim()
        val imdbEpisode = Regex("^(tt[0-9]+):.+$").matchEntire(raw)?.groupValues?.get(1)
        if (imdbEpisode != null) return ContentIdentity.imdb(ContentKind.SERIES, imdbEpisode)
        val tmdbId = Regex("^tmdb:([1-9][0-9]*)(?::.*)?$", RegexOption.IGNORE_CASE)
            .matchEntire(raw)?.groupValues?.get(1)
        if (tmdbId != null) return ContentIdentity.tmdb(ContentKind.SERIES, tmdbId)
        return titleIdentity(raw, type, addonInstallationId)
    }

    private fun legacy(type: String, id: String): String =
        "legacy/${encode(type.trim().lowercase(Locale.ROOT))}/${encode(id.trim())}"

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}

/** Opaque, stable provider-install identifier; never use an addon display name as identity. */
fun addonInstallationKey(baseUrl: String): String {
    val raw = baseUrl.trim().trimEnd('/')
    val normalized = runCatching {
        val uri = URI(raw).normalize()
        val host = uri.host ?: return@runCatching raw
        buildString {
            append(uri.scheme?.lowercase(Locale.ROOT) ?: "https")
            append("://")
            uri.rawUserInfo?.let { append(it).append('@') }
            val normalizedHost = host.lowercase(Locale.ROOT)
            append(if (normalizedHost.contains(':') && !normalizedHost.startsWith('[')) "[$normalizedHost]" else normalizedHost)
            if (uri.port >= 0) append(':').append(uri.port)
            uri.rawPath?.trimEnd('/')?.let(::append)
            uri.rawQuery?.let { append('?').append(it) }
        }
    }.getOrDefault(raw)
    return MessageDigest.getInstance("SHA-256")
        .digest(normalized.toByteArray(Charsets.UTF_8)).take(12)
        .joinToString("") { "%02x".format(it) }
}
