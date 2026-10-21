package com.saab.tv.domain.schema

import org.junit.Assert.*
import org.junit.Test

class ContentIdentityTest {

    @Test fun addonInstallationKeyNormalizesOriginButPreservesCaseSensitivePath() {
        assertEquals(
            addonInstallationKey("HTTPS://Provider.Example/manifest.json/"),
            addonInstallationKey("https://provider.example/manifest.json")
        )
        assertNotEquals(
            addonInstallationKey("https://provider.example/Manifest.json"),
            addonInstallationKey("https://provider.example/manifest.json")
        )
    }
    @Test
    fun providerMediaKindsNormalizeAliasesAndRejectUnknownValues() {
        assertEquals(ContentKind.MOVIE, ContentKind.fromProvider(" Film "))
        assertEquals(ContentKind.SERIES, ContentKind.fromProvider("TVShow"))
        assertEquals(ContentKind.EPISODE, ContentKind.fromProvider("episode"))
        assertEquals(ContentKind.PERSON, ContentKind.fromProvider("people"))
        assertNull(ContentKind.fromProvider("unknown-kind"))
    }

    @Test
    fun tmdbIdentityIncludesMediaKindSoMovieAndSeriesNeverCollide() {
        val movie = requireNotNull(ContentIdentity.tmdb(ContentKind.MOVIE, 42))
        val series = requireNotNull(ContentIdentity.tmdb(ContentKind.SERIES, "42"))

        assertEquals("tmdb/movie//42", movie.stableKey)
        assertEquals("tmdb/series//42", series.stableKey)
        assertNotEquals(movie.stableKey, series.stableKey)
        assertNull(ContentIdentity.tmdb(ContentKind.MOVIE, 0))
        assertNull(ContentIdentity.tmdb(ContentKind.MOVIE, -1))
        assertNull(ContentIdentity.tmdb(ContentKind.MOVIE, "not-a-number"))
    }

    @Test
    fun imdbIdentityTrimsIdAndRejectsInvalidNamespaceValues() {
        val id = requireNotNull(ContentIdentity.imdb(ContentKind.MOVIE, " tt12345 "))
        assertEquals(IdentityNamespace.IMDB, id.namespace)
        assertEquals("tt12345", id.externalId)
        assertNull(ContentIdentity.imdb(ContentKind.MOVIE, "12345"))
        assertNull(ContentIdentity.imdb(ContentKind.MOVIE, "tt12x"))
    }

    @Test
    fun addonIdentityScopesOpaqueIdsByInstallationAndEscapesStableKeyParts() {
        val first = requireNotNull(ContentIdentity.addon("one/addon", ContentKind.MOVIE, "same:id"))
        val second = requireNotNull(ContentIdentity.addon("two", ContentKind.MOVIE, "same:id"))

        assertEquals("addon/movie/one%2Faddon/same%3Aid", first.stableKey)
        assertNotEquals(first.stableKey, second.stableKey)
        assertNull(ContentIdentity.addon(" ", ContentKind.MOVIE, "id"))
        assertNull(ContentIdentity.addon("one", ContentKind.MOVIE, " "))
    }

    @Test
    fun genericFactoryUsesNamespaceValidationAndIdentityEqualityIsStable() {
        val imdb = requireNotNull(ContentIdentity.from(IdentityNamespace.IMDB, ContentKind.MOVIE, "tt123"))
        val tmdb = requireNotNull(ContentIdentity.from(IdentityNamespace.TMDB, ContentKind.SERIES, "12"))
        val addon = requireNotNull(
            ContentIdentity.from(IdentityNamespace.ADDON, ContentKind.EPISODE, "opaque", "install-1")
        )

        assertEquals(imdb, ContentIdentity.imdb(ContentKind.MOVIE, "tt123"))
        assertEquals(imdb.hashCode(), ContentIdentity.imdb(ContentKind.MOVIE, "tt123")?.hashCode())
        assertNotEquals(imdb, tmdb)
        assertNotEquals(addon, ContentIdentity.addon("install-2", ContentKind.EPISODE, "opaque"))
        assertNull(ContentIdentity.from(IdentityNamespace.TMDB, ContentKind.MOVIE, "0"))
        assertNull(ContentIdentity.from(IdentityNamespace.ADDON, ContentKind.MOVIE, "opaque"))
    }

    @Test
    fun playableIdentityKeepsEpisodesDistinctForImdbAndTmdbSeries() {
        val imdbEpisodeOne = CanonicalEntityKey.playable("tt123:1:1:0", "series")
        val imdbEpisodeTwo = CanonicalEntityKey.playable("tt123:1:2:0", "series")
        val tmdbEpisodeOne = CanonicalEntityKey.playable("tmdb:42:1:1:0", "series")
        val tmdbEpisodeTwo = CanonicalEntityKey.playable("tmdb:42:1:2:0", "series")

        assertNotEquals(imdbEpisodeOne, imdbEpisodeTwo)
        assertNotEquals(tmdbEpisodeOne, tmdbEpisodeTwo)
        assertTrue(imdbEpisodeOne.startsWith("episode/imdb/series"))
        assertTrue(tmdbEpisodeOne.startsWith("episode/tmdb/series"))
        assertEquals(CanonicalEntityKey.title("tmdb:42", "series"),
            CanonicalEntityKey.playable("tmdb:42", "series"))
    }
}
