package com.saab.tv.ui.components

import com.saab.tv.domain.schema.ContentIdentity
import com.saab.tv.domain.schema.ContentKind
import com.saab.tv.domain.schema.TitleCardProjection
import com.saab.tv.data.model.stremio.MetaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleCardBadgePolicyTest {
    @Test fun watchlistBadgeIsVisibleOnlyForAnItemInTheActiveProfileSet() {
        val activeProfileWatchlist = setOf("tt-movie", "cinemeta:series")
        assertTrue(shouldShowWatchlistBadge("tt-movie", activeProfileWatchlist))
        assertTrue(shouldShowWatchlistBadge("cinemeta:series", activeProfileWatchlist))
        assertFalse(shouldShowWatchlistBadge("other-title", activeProfileWatchlist))
        assertFalse(shouldShowWatchlistBadge("tt-movie", emptySet()))
        assertFalse(shouldShowWatchlistBadge(null, activeProfileWatchlist))
    }

    @Test fun watchedBadgeAndWatchlistBadgeRemainIndependentStates() {
        assertTrue(shouldShowWatchedBadge(true, "title", emptySet()))
        assertFalse(shouldShowWatchedBadge(false, "title", emptySet()))
        assertTrue(shouldShowWatchlistBadge("title", setOf("title")))
        assertFalse(shouldShowWatchlistBadge("title", emptySet()))
    }

    @Test fun verifiedCanonicalAliasesDriveBadgesWhenCardUsesDifferentProviderId() {
        val verifiedTmdbIdentity = "tmdb/movie//42"
        assertTrue(shouldShowWatchedBadge(
            explicitWatched = false, itemId = "tt1234567", watchedIds = setOf(verifiedTmdbIdentity),
            canonicalIdentityKey = verifiedTmdbIdentity
        ))
        assertTrue(shouldShowWatchlistBadge(
            itemId = "tt1234567", watchlistIds = setOf(verifiedTmdbIdentity),
            canonicalIdentityKey = verifiedTmdbIdentity
        ))
    }

    @Test fun canonicalOnlySeriesProjectionKeepsLandscapeAndBadgeIdentity() {
        val identity = requireNotNull(ContentIdentity.tmdb(ContentKind.SERIES, "42"))
        val projection = TitleCardProjection(
            presentationKey = identity.stableKey,
            identity = identity,
            kind = ContentKind.SERIES,
            title = "Series",
            synopsis = null,
            artwork = emptyList()
        )

        val resolved = resolveCardProjection(projection, previewItem = null)

        assertEquals(identity.stableKey, resolved?.presentationKey)
        assertTrue(usesSeriesPosterStack(type = null, canonicalKind = resolved?.kind))
        assertTrue(shouldShowWatchedBadge(
            explicitWatched = false,
            itemId = null,
            watchedIds = setOf(identity.stableKey),
            canonicalIdentityKey = resolved?.presentationKey
        ))
        assertTrue(shouldShowWatchlistBadge(
            itemId = null,
            watchlistIds = setOf(identity.stableKey),
            canonicalIdentityKey = resolved?.presentationKey
        ))

        val providerPreview = MetaItem(id = "tt1234567", type = "series", name = "Provider title")
        val providerFallback = resolveCardProjection(canonicalProjection = null, previewItem = providerPreview)
        assertEquals(
            requireNotNull(ContentIdentity.imdb(ContentKind.SERIES, providerPreview.id)).stableKey,
            providerFallback?.presentationKey
        )
    }
}
