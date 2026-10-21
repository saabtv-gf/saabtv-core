package com.saab.tv.domain

import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.data.model.stremio.MetaTrailer
import com.saab.tv.data.model.stremio.MetaTrailerStream
import com.saab.tv.domain.CategoryRow
import com.saab.tv.domain.HomeRow
import com.saab.tv.domain.schema.CatalogCardProjection
import com.saab.tv.domain.schema.IdentityNamespace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CatalogCardProjectionTest {
    @Test fun verifiedTitleUsesCanonicalProjectionAndPreservesTrailerActionMetadata() {
        val providerItem = MetaItem(
            id = "tt1234567",
            type = "movie",
            name = "Catalog title",
            trailers = listOf(MetaTrailer(source = "youtube-key", type = "Trailer")),
            trailerStreams = listOf(MetaTrailerStream(title = "Official", ytId = "official-key"))
        )

        val card = CatalogCardProjection.from(providerItem)
        assertNotNull(card.content)
        assertEquals("imdb/movie//tt1234567", card.presentationKey)
        assertEquals("youtube-key", card.asActionItem().trailers?.single()?.source)
        assertEquals("official-key", card.asActionItem().trailerStreams?.single()?.ytId)
    }

    @Test fun typedTitleCanBeCopiedWithoutCompatibilityPayload() {
        val original = CatalogCardProjection.from(MetaItem(
            id = "tt7654321",
            type = "series",
            name = "Typed show"
        ))
        val typedOnly = CatalogCardProjection(content = original.content)

        assertEquals("imdb/series//tt7654321", typedOnly.presentationKey)
        assertEquals("tt7654321", typedOnly.asActionItem().id)
    }

    @Test fun addonTitleUsesInstallationIdentityWithoutChangingPlaybackId() {
        val card = CatalogCardProjection.from(MetaItem(
            id = "provider-title-1",
            type = "series",
            name = "Addon show",
            addonBaseUrl = "https://addon.example/stremio"
        ))

        assertEquals(IdentityNamespace.ADDON, card.content?.identity?.namespace)
        assertEquals("provider-title-1", card.asActionItem().id)
        assertEquals("https://addon.example/stremio", card.asActionItem().addonBaseUrl)
    }

    @Test fun providerHomeRowIsConvertedToTypedCatalogRowForUiState() {
        val row = CategoryRow.fromHomeRow(
            HomeRow(
                configId = "featured",
                title = "Featured",
                items = listOf(MetaItem(id = "tt1234567", type = "movie", name = "Canonical title")),
                order = 4
            )
        )

        assertEquals("featured", row.id)
        assertEquals("Featured", row.title)
        assertEquals("imdb/movie//tt1234567", row.items.single().presentationKey)
        assertEquals("Canonical title", row.items.single().asActionItem().name)
    }

    @Test fun typedCatalogRowDefaultsAndHomeRowOrderArePreserved() {
        val row = CategoryRow(
            id = "latest",
            title = "Latest",
            order = 7,
            items = emptyList()
        )
        val homeRow: HomeRowItem = row

        assertEquals(7, homeRow.order)
        assertEquals("", row.catalogUrl)
        assertEquals(false, row.supportsSkip)
        assertEquals(true, row.isInfiniteScrollingEnabled)
    }

    @Test fun unscopedOpaqueTitleStaysAtCompatibilityEdge() {
        val original = MetaItem(id = "private:catalog-id", type = "movie", name = "Opaque")
        val card = CatalogCardProjection.from(original)

        assertNull(card.content)
        assertEquals(original, card.asActionItem())
        assertEquals("legacy/movie/private:catalog-id", card.presentationKey)
    }

    @Test fun absentTypedAndCompatibilityPayloadFailsExplicitly() {
        val invalid = CatalogCardProjection(content = null)
        assertThrows(IllegalArgumentException::class.java) { invalid.presentationKey }
        assertThrows(IllegalArgumentException::class.java) { invalid.asActionItem() }
    }
}
