package com.saab.tv.ui.search

import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.domain.schema.canonicalPresentationKey
import com.saab.tv.ui.components.firstItemIndexByCanonicalKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchFocusPolicyTest {
    @Test fun restoresTheRequestedCardFromTheBatchIndex() {
        val items = (0..500).map { index ->
            MetaItem("title-$index", "movie", "Title $index")
        }
        val index = firstItemIndexByCanonicalKey(items)

        assertEquals(items[317], resolveSearchFocusItem(items, index, items[317].canonicalPresentationKey()))
    }

    @Test fun missingOrStaleIdentityFallsBackToTheFirstAvailableResult() {
        val items = listOf(MetaItem("available", "series", "Available"))
        val fallback = MetaItem("fallback", "movie", "Fallback")

        assertEquals(fallback, resolveSearchFocusItem(items, emptyMap(), "removed-title", fallback))
    }

    @Test fun duplicateCanonicalIdentityUsesTheFirstResultLikeTheIndexBuilder() {
        val first = MetaItem("duplicate", "movie", "First")
        val second = MetaItem("duplicate", "movie", "Second")
        val items = listOf(first, second)
        val index = firstItemIndexByCanonicalKey(items)

        assertEquals(first, resolveSearchFocusItem(items, index, first.canonicalPresentationKey()))
    }

    @Test fun emptyResultsReturnFallbackOrNullWithoutInvalidIndexAccess() {
        val fallback = MetaItem("fallback", "series", "Fallback")

        assertEquals(fallback, resolveSearchFocusItem(emptyList(), emptyMap(), "missing", fallback))
        assertNull(resolveSearchFocusItem(emptyList(), emptyMap(), "missing"))
    }

    @Test fun naturalLanguageInterpretationsExposeOnlySupportedTermsInInputOrder() {
        val chips = searchInterpretationChips("popular Telugu thriller movies")

        assertEquals(listOf("popular", "telugu", "thriller", "movies"), chips.map { it.phrase })
        assertTrue(searchInterpretationChips("The popular movie night").isNotEmpty())
        assertTrue(searchInterpretationChips("a title with no filters").isEmpty())
    }

    @Test fun removingInterpretationPreservesTheRemainingQueryAndPunctuation() {
        assertEquals("popular thriller movies", removeSearchInterpretation("popular Telugu thriller movies", "Telugu"))
        assertEquals("science fiction", removeSearchInterpretation("science fiction movies", "movies"))
        assertEquals("Batman Begins", removeSearchInterpretation("Batman Begins", "movie"))
    }
}
