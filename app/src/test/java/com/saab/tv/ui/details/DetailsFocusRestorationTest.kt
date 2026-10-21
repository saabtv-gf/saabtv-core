package com.saab.tv.ui.details

import org.junit.Assert.assertEquals
import org.junit.Test

class DetailsFocusRestorationTest {
    @Test fun absentRelatedRowsWaitUntilMetadataFinishesBeforeFallingBack() {
        assertEquals(
            DetailsFocusRestoreTarget.Pending,
            resolveDetailsFocusRestoreTarget(
                rowKey = "tmdb_recs",
                itemKey = "tmdb/movie/42",
                fallbackIndex = 3,
                metadataPending = true,
                rowKeys = listOf("hero"),
                itemsByRow = emptyMap()
            )
        )
        assertEquals(
            DetailsFocusRestoreTarget.Hero,
            resolveDetailsFocusRestoreTarget(
                rowKey = "tmdb_recs",
                itemKey = "tmdb/movie/42",
                fallbackIndex = 3,
                metadataPending = false,
                rowKeys = listOf("hero"),
                itemsByRow = emptyMap()
            )
        )
    }

    @Test fun relatedTitleIdentityWinsOverAnIndexWhenTheRailReorders() {
        assertEquals(
            DetailsFocusRestoreTarget.RowItem(rowIndex = 2, itemIndex = 0),
            resolveDetailsFocusRestoreTarget(
                rowKey = "tmdb_recs",
                itemKey = "tmdb/movie/42",
                fallbackIndex = 4,
                metadataPending = false,
                rowKeys = listOf("hero", "tmdb_cast", "tmdb_recs", "tmdb_collection"),
                itemsByRow = mapOf("tmdb_recs" to listOf("tmdb/movie/42", "tmdb/series/17"))
            )
        )
    }

    @Test fun missingRelatedTitleUsesNearestFocusableSiblingInsteadOfTheStructuralDivider() {
        assertEquals(
            DetailsFocusRestoreTarget.RowItem(rowIndex = 1, itemIndex = 2),
            resolveDetailsFocusRestoreTarget(
                rowKey = "tmdb_cast",
                itemKey = "cast_404",
                fallbackIndex = 2,
                metadataPending = false,
                rowKeys = listOf("hero", "tmdb_cast"),
                itemsByRow = mapOf("tmdb_cast" to listOf("crew_10", null, "cast_11"))
            )
        )
    }

    @Test fun exactCastIdentityRestoresItsCurrentIndexAfterCastOrderChanges() {
        assertEquals(
            DetailsFocusRestoreTarget.RowItem(rowIndex = 1, itemIndex = 0),
            resolveDetailsFocusRestoreTarget(
                rowKey = "tmdb_cast",
                itemKey = "crew_21",
                fallbackIndex = 2,
                metadataPending = false,
                rowKeys = listOf("hero", "tmdb_cast"),
                itemsByRow = mapOf("tmdb_cast" to listOf("crew_21", "crew_10", "cast_11"))
            )
        )
    }
}
