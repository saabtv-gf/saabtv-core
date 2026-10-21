package com.saab.tv.domain.schema

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/** A content kind is part of identity; TMDB movie/TV IDs occupy separate namespaces. */
enum class ContentKind(val keyPart: String) {
    MOVIE("movie"),
    SERIES("series"),
    EPISODE("episode"),
    PERSON("person");

    companion object {
        fun fromProvider(value: String): ContentKind? = when (value.trim().lowercase(Locale.ROOT)) {
            "movie", "film" -> MOVIE
            "series", "tv", "show", "tvshow" -> SERIES
            "episode" -> EPISODE
            "person", "people" -> PERSON
            else -> null
        }
    }
}

enum class IdentityNamespace(val keyPart: String) { IMDB("imdb"), TMDB("tmdb"), ADDON("addon") }

/** Stable provider-qualified identity. Addon installation IDs prevent opaque-ID collisions. */
class ContentIdentity private constructor(
    val namespace: IdentityNamespace,
    val kind: ContentKind,
    val externalId: String,
    val providerInstallationId: String?
) {
    override fun equals(other: Any?): Boolean =
        other is ContentIdentity && namespace == other.namespace && kind == other.kind &&
            externalId == other.externalId && providerInstallationId == other.providerInstallationId

    override fun hashCode(): Int =
        (((namespace.hashCode() * 31 + kind.hashCode()) * 31 + externalId.hashCode()) * 31) +
            (providerInstallationId?.hashCode() ?: 0)

    val stableKey: String
        get() = listOf(
            namespace.keyPart,
            kind.keyPart,
            providerInstallationId.orEmpty(),
            externalId
        ).joinToString("/") { URLEncoder.encode(it, StandardCharsets.UTF_8.name()) }

    companion object {
        fun from(
            namespace: IdentityNamespace,
            kind: ContentKind,
            externalId: String,
            providerInstallationId: String? = null
        ): ContentIdentity? = when (namespace) {
            IdentityNamespace.IMDB -> imdb(kind, externalId)
            IdentityNamespace.TMDB -> tmdb(kind, externalId)
            IdentityNamespace.ADDON -> addon(providerInstallationId.orEmpty(), kind, externalId)
        }

        fun imdb(kind: ContentKind, id: String): ContentIdentity? {
            val value = id.trim()
            if (!value.matches(Regex("tt[0-9]+"))) return null
            return ContentIdentity(IdentityNamespace.IMDB, kind, value, null)
        }

        fun tmdb(kind: ContentKind, id: Int): ContentIdentity? =
            if (id > 0) ContentIdentity(IdentityNamespace.TMDB, kind, id.toString(), null) else null

        fun addon(installationId: String, kind: ContentKind, opaqueId: String): ContentIdentity? {
            val install = installationId.trim()
            val external = opaqueId.trim()
            if (install.isEmpty() || external.isEmpty()) return null
            return ContentIdentity(IdentityNamespace.ADDON, kind, external, install)
        }

        fun tmdb(kind: ContentKind, id: String): ContentIdentity? =
            id.trim().toIntOrNull()?.let { tmdb(kind, it) }
    }
}
