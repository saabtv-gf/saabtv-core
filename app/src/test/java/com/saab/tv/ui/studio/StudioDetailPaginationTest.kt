package com.saab.tv.ui.studio

import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.data.tmdb.TmdbDiscoverRail
import com.saab.tv.data.tmdb.TmdbEntityDetail
import com.saab.tv.domain.schema.toCanonicalProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioDetailPaginationTest {
    @Test fun concurrentlyCompletedRailsMergeIntoTheLatestState() {
        val popularFirst = title(88101, "Popular first")
        val recentFirst = title(88102, "Recent first")
        val popularNext = title(88103, "Popular next")
        val recentNext = title(88104, "Recent next")
        val initial = StudioDetailState.Success(
            entity = entity,
            rails = listOf(
                rail("popular", popularFirst),
                rail("recent", recentFirst)
            )
        )

        val afterPopular = initial.appendDiscoverPage(
            mediaType = "movie", railType = "popular", page = 2,
            pageItems = listOf(popularNext), totalPages = 5
        )
        val afterRecent = afterPopular.appendDiscoverPage(
            mediaType = "movie", railType = "recent", page = 2,
            pageItems = listOf(recentNext), totalPages = 5
        )

        assertEquals(listOf("88101", "88103"), afterRecent.rails[0].items.map { it.identity.externalId })
        assertEquals(listOf("88102", "88104"), afterRecent.rails[1].items.map { it.identity.externalId })
        assertTrue(afterRecent.rails.all { it.currentPage == 2 && it.hasMore })
    }

    @Test fun pageMergeDeduplicatesTitlesAndStopsAtThePaginationLimit() {
        val existing = title(88105, "Existing")
        val updated = rail("popular", existing).let {
            StudioDetailState.Success(entity, listOf(it))
        }.appendDiscoverPage(
            mediaType = "movie", railType = "popular", page = 3,
            pageItems = listOf(title(88105, "Duplicate"), title(88106, "New")), totalPages = 10
        )
        val result = updated.rails.single()

        assertEquals(listOf("88105", "88106"), result.items.map { it.identity.externalId })
        assertEquals(3, result.currentPage)
        assertFalse(result.hasMore)
    }

    private fun rail(type: String, item: com.saab.tv.domain.schema.TitleCardProjection) =
        TmdbDiscoverRail("movie", type, listOf(item), currentPage = 1, hasMore = true)

    private fun title(id: Int, name: String) =
        requireNotNull(MetaItem("tmdb:$id", "movie", name).toCanonicalProjection())

    private companion object {
        val entity = TmdbEntityDetail(1, "company", "Studio", null, null, null, null)
    }
}
