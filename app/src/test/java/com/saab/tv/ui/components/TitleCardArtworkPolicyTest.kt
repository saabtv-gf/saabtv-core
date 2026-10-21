package com.saab.tv.ui.components

import com.saab.tv.domain.schema.ArtworkReference
import com.saab.tv.domain.schema.ArtworkRole
import com.saab.tv.domain.schema.ContentIdentity
import com.saab.tv.domain.schema.ContentKind
import com.saab.tv.domain.schema.TitleCardProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TitleCardArtworkPolicyTest {
    @Test
    fun blankCanonicalArtworkFallsBackToProviderAndIgnoresBlankFallbacks() {
        val identity = requireNotNull(ContentIdentity.tmdb(ContentKind.MOVIE, "42"))
        val projection = TitleCardProjection(
            presentationKey = identity.stableKey,
            identity = identity,
            kind = ContentKind.MOVIE,
            title = "Artwork test",
            synopsis = null,
            artwork = listOf(
                ArtworkReference(ArtworkRole.POSTER, " ", "tmdb"),
                ArtworkReference(ArtworkRole.BACKDROP, "", "tmdb"),
                ArtworkReference(ArtworkRole.LOGO, "tmdb-logo", "tmdb")
            )
        )

        assertEquals(
            "provider-poster",
            resolveCardArtworkUrl(projection, ArtworkRole.POSTER, "provider-poster")
        )
        assertEquals(
            "provider-backdrop",
            resolveCardArtworkUrl(projection, ArtworkRole.BACKDROP, "provider-backdrop")
        )
        assertEquals(
            "tmdb-logo",
            resolveCardArtworkUrl(projection, ArtworkRole.LOGO, "provider-logo")
        )
        assertNull(resolveCardArtworkUrl(projection, ArtworkRole.BACKDROP, "  "))
    }
}
