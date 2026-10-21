package com.saab.tv.domain.schema

import com.saab.tv.data.model.CanonicalTitleEntity
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.data.model.stremio.MetaTrailer
import com.saab.tv.data.model.stremio.MetaTrailerStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalTitleProjectionTest {
    private val movie = requireNotNull(ContentIdentity.imdb(ContentKind.MOVIE, "tt1234567"))

    @Test fun canonicalArtworkRolesNeverSubstitutePosterForBackdropOrLogo() {
        val row = CanonicalTitleEntity(
            identityKey = movie.stableKey,
            locale = "en",
            kind = "movie",
            poster = "poster.jpg", posterSource = "TMDB",
            backdrop = null, backdropSource = null,
            logo = "logo.png", logoSource = "TMDB",
            lastFetchedAtMillis = 1L
        )
        val projection = row.toTitleCardProjection(movie, "Fallback", listOf(
            ArtworkReference(ArtworkRole.POSTER, "fallback-poster.jpg", "Cinemeta"),
            ArtworkReference(ArtworkRole.BACKDROP, "fallback-backdrop.jpg", "Cinemeta")
        ))

        assertEquals("poster.jpg", projection.artwork(ArtworkRole.POSTER)?.locator)
        assertEquals("fallback-backdrop.jpg", projection.artwork(ArtworkRole.BACKDROP)?.locator)
        assertEquals("logo.png", projection.artwork(ArtworkRole.LOGO)?.locator)
        assertFalse(projection.artwork.any { it.locator == "poster.jpg" && it.role == ArtworkRole.BACKDROP })
    }

    @Test fun missingCanonicalFieldsFallBackWithoutFabricatingFacts() {
        val row = CanonicalTitleEntity(movie.stableKey, "en", "movie", lastFetchedAtMillis = 1L)
        val projection = row.toTitleCardProjection(movie, "Cinemeta title")
        assertEquals("Cinemeta title", projection.title)
        assertNull(projection.synopsis)
        assertNull(projection.rating)
        assertNull(projection.runtimeEstimateMillis)
        assertTrue(projection.artwork.isEmpty())
    }

    @Test fun canonicalYearProjectionAcceptsOnlyValuesConsistentWithPrecision() {
        val year = CanonicalTitleEntity(
            movie.stableKey, "en", "movie", releaseDate = "2025", releaseDateSource = "TMDB", releaseDatePrecision = "YEAR",
            lastFetchedAtMillis = 1L
        ).toTitleCardProjection(movie, "Fallback")
        val date = CanonicalTitleEntity(
            movie.stableKey, "en", "movie", releaseDate = "2025-04-03", releaseDateSource = "TMDB", releaseDatePrecision = "DATE",
            lastFetchedAtMillis = 1L
        ).toTitleCardProjection(movie, "Fallback")
        val malformed = CanonicalTitleEntity(
            movie.stableKey, "en", "movie", releaseDate = "2025-04-03", releaseDateSource = "TMDB", releaseDatePrecision = "YEAR",
            lastFetchedAtMillis = 1L
        ).toTitleCardProjection(movie, "Fallback")
        val ambiguous = CanonicalTitleEntity(
            movie.stableKey, "en", "movie", releaseDate = "2025-04", releaseDateSource = "TMDB", releaseDatePrecision = null,
            lastFetchedAtMillis = 1L
        ).toTitleCardProjection(movie, "Fallback")

        assertEquals("2025", year.year)
        assertEquals("2025", date.year)
        assertNull(malformed.year)
        assertNull(ambiguous.year)
    }

    @Test fun tmdbIdsRemainTypedAtCompatibilityEdge() {
        val projection = requireNotNull(MetaItem(
            id = "tmdb:42", type = "series", name = "Show", poster = "p", background = "b"
        ).toCanonicalProjection())
        assertEquals(ContentKind.SERIES, projection.identity.kind)
        assertEquals(IdentityNamespace.TMDB, projection.identity.namespace)
        val compatible = projection.asMetaItem()
        assertEquals("tmdb:42", compatible.id)
        assertEquals("series", compatible.type)
        assertEquals("p", compatible.poster)
        assertEquals("b", compatible.background)
    }

    @Test fun providerTrailerDtosAreAdaptedToTypedReferencesAndBack() {
        val item = MetaItem(
            id = "tt1234567",
            type = "movie",
            name = "Title",
            trailers = listOf(MetaTrailer(source = "legacy-key", type = "Trailer")),
            trailerStreams = listOf(MetaTrailerStream(
                title = "Official", ytId = "video-id", externalUrl = "https://video.example/watch", url = null
            ))
        )

        val projection = requireNotNull(item.toCanonicalProjection())
        assertEquals(listOf(TrailerReference("legacy-key", "Trailer")), projection.trailers)
        assertEquals(
            listOf(TrailerStreamReference("Official", "video-id", "https://video.example/watch", null)),
            projection.trailerStreams
        )
        assertEquals(item.trailers, projection.asMetaItem().trailers)
        assertEquals(item.trailerStreams, projection.asMetaItem().trailerStreams)
    }

    @Test fun opaqueAddonIdsRequireAndUseInstallationScope() {
        val unscoped = MetaItem(id = "catalog:42", type = "movie", name = "Title")
        assertNull(unscoped.toCanonicalProjection())
        val one = requireNotNull(unscoped.copy(addonBaseUrl = "https://addon.example/a").toCanonicalProjection())
        val other = requireNotNull(unscoped.copy(addonBaseUrl = "https://addon.example/b").toCanonicalProjection())
        assertEquals(IdentityNamespace.ADDON, one.identity.namespace)
        assertTrue(one.identity.stableKey != other.identity.stableKey)
        assertEquals("catalog:42", one.asMetaItem().id)
    }

    @Test fun metadataOriginDistinguishesCinemetaFromOtherProviders() {
        assertEquals(
            MetadataSource.CINEMETA,
            MetaItem("tt1234567", "movie", "Title", addonBaseUrl = "https://v3-cinemeta.strem.io")
                .metadataSource()
        )
        assertEquals(
            MetadataSource.ADDON,
            MetaItem("tt1234567", "movie", "Title", addonBaseUrl = "https://addon.example")
                .metadataSource()
        )
        assertEquals(
            MetadataSource.LEGACY,
            MetaItem("tt1234567", "movie", "Title").metadataSource()
        )
    }

    @Test fun cinemetaBackdropAndLogoAreDirectCanonicalArtworkReferences() {
        val projection = requireNotNull(MetaItem(
            id = "tt1234567",
            type = "movie",
            name = "Cinemeta title",
            background = "https://cinemeta.test/backdrop.jpg",
            logo = "https://cinemeta.test/logo.png",
            addonBaseUrl = "https://v3-cinemeta.strem.io"
        ).toCanonicalProjection())

        assertEquals("https://cinemeta.test/backdrop.jpg", projection.artwork(ArtworkRole.BACKDROP)?.locator)
        assertEquals("https://cinemeta.test/logo.png", projection.artwork(ArtworkRole.LOGO)?.locator)
        assertEquals(MetadataSource.CINEMETA.name, projection.artwork(ArtworkRole.BACKDROP)?.source)
        assertEquals(MetadataSource.CINEMETA.name, projection.artwork(ArtworkRole.LOGO)?.source)
    }

    @Test fun providerSpecificFactsKeepRatingSourceAndScale() {
        val imdbRating = MetaItem(id = "tt1234567", type = "movie", name = "Title", imdbRating = "8.1")
            .toCanonicalProjection()
        val unknownRating = MetaItem(id = "tt1234567", type = "movie", name = "Title", imdbRating = "12.0")
            .toCanonicalProjection()
        assertEquals("imdb", imdbRating?.rating?.source)
        assertEquals("0-10", imdbRating?.rating?.scale)
        assertNull(unknownRating?.rating)
    }

    @Test fun compatibilityProjectionHandlesMalformedMetadataAndAllContentKinds() {
        val person = requireNotNull(ContentIdentity.imdb(ContentKind.PERSON, "tt1234567"))
        val personProjection = TitleCardProjection(
            presentationKey = person.stableKey,
            identity = person,
            kind = ContentKind.PERSON,
            title = "Person",
            synopsis = null,
            artwork = emptyList(),
            runtimeEstimateMillis = 10_000
        )
        assertEquals("person", personProjection.asMetaItem().type)
        assertEquals("1", personProjection.asMetaItem().runtime)

        assertNull(MetaItem(id = "tt1234567", type = "episode", name = "Episode").toCanonicalProjection())
        val hoursOnly = requireNotNull(MetaItem(
            id = "tt1234567", type = "movie", name = "Title", runtime = "2h"
        ).toCanonicalProjection())
        assertEquals(120L * 60_000L, hoursOnly.runtimeEstimateMillis)
    }

    @Test fun canonicalProjectionRejectsUnsupportedYearPrecisionAndSafelyDecodesGenres() {
        val row = CanonicalTitleEntity(
            identityKey = movie.stableKey,
            locale = "en",
            kind = "movie",
            releaseDate = "2025-04",
            releaseDatePrecision = "MONTH",
            releaseDateSource = "TMDB",
            ratingValue = 8.4,
            ratingSource = " tMdB ",
            ratingScale = "0-10",
            runtimeEstimateMillis = 90_000,
            runtimeSource = "TMDB",
            genresJson = "[\"Drama\", \"\", 7, \"Drama\", null]",
            genresSource = "TMDB",
            lastFetchedAtMillis = 1L
        )

        val projection = row.toTitleCardProjection(movie, "Fallback")
        assertNull(projection.year)
        assertEquals(listOf("Drama"), projection.genres)
        assertEquals("tmdb", projection.rating?.source)
        assertEquals("1", projection.asMetaItem().runtime)

        val unparseable = row.copy(
            ratingSource = " Open Movie Database ",
            genresJson = "not-json"
        ).toTitleCardProjection(movie, "Fallback")
        assertEquals("open movie database", unparseable.rating?.source)
        assertTrue(unparseable.genres.isEmpty())
    }
}
