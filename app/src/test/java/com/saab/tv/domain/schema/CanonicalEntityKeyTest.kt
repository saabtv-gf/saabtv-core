package com.saab.tv.domain.schema

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalEntityKeyTest {
    @Test fun titleAndPlayableKeysKeepCanonicalAndExactEpisodeIdentitySeparate() {
        val title = CanonicalEntityKey.title("tt1234567:2:5", "series")
        val playable = CanonicalEntityKey.playable("tt1234567:2:5", "series")

        assertEquals(ContentIdentity.imdb(ContentKind.SERIES, "tt1234567")?.stableKey, title)
        assertTrue(playable.startsWith("episode/"))
        assertNotEquals(title, playable)
        assertNotEquals(playable, CanonicalEntityKey.playable("tt1234567:2:6", "series"))
    }

    @Test fun addonIdentityIsInstallationScopedAndProfileKeysEncodeInput() {
        val first = CanonicalEntityKey.title("opaque:1", "movie", "install-a")
        val second = CanonicalEntityKey.title("opaque:1", "movie", "install-b")

        assertNotEquals(first, second)
        assertEquals("profile/profile%2Fone", CanonicalEntityKey.profile("profile/one"))
        assertEquals("profile-runtime/profile%2Fone", CanonicalEntityKey.profileRuntime("profile/one"))
    }

    @Test fun invalidAndUnrecognizedIdentitiesUseDeterministicLegacyKeys() {
        assertTrue(CanonicalEntityKey.title("", "unknown").startsWith("legacy/"))
        assertEquals(
            CanonicalEntityKey.playable("opaque", "movie"),
            CanonicalEntityKey.title("opaque", "movie")
        )
    }

    @Test fun providerAndPlayableSeriesIdentityHelpersCoverValidAndFallbackKinds() {
        val imdbSeries = requireNotNull(ContentIdentity.imdb(ContentKind.SERIES, "tt1234567"))
        val tmdbSeries = requireNotNull(ContentIdentity.tmdb(ContentKind.SERIES, 42))
        assertEquals(imdbSeries, CanonicalEntityKey.titleIdentity("tt1234567:2:5", "series"))
        assertEquals(tmdbSeries, CanonicalEntityKey.titleIdentity("tmdb:42:2:5", "series"))
        assertEquals(
            ContentIdentity.addon("install-a", ContentKind.MOVIE, "opaque"),
            CanonicalEntityKey.titleIdentity("opaque", "movie", "install-a")
        )
        assertEquals(null, CanonicalEntityKey.titleIdentity("opaque", "unknown"))

        assertEquals(imdbSeries, CanonicalEntityKey.playableSeriesIdentity("tt1234567:2:5", "series"))
        assertEquals(tmdbSeries, CanonicalEntityKey.playableSeriesIdentity("tmdb:42:2:5", "series"))
        assertEquals(
            ContentIdentity.addon("install-a", ContentKind.SERIES, "opaque"),
            CanonicalEntityKey.playableSeriesIdentity("opaque", "series", "install-a")
        )
        assertEquals(null, CanonicalEntityKey.playableSeriesIdentity("tt1234567", "movie"))
    }
}
