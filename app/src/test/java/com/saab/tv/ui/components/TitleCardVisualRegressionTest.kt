package com.saab.tv.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.ui.home.ViewMoreCard
import org.junit.Assert.assertEquals
import android.app.Application
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land")
class TitleCardVisualRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun seriesStackKeepsTheSameMeasuredHeightAsMovieCardsInBothShapes() {
        compose.setContent {
            androidx.tv.material3.MaterialTheme {
                Column {
                    Row {
                        CompositionLocalProvider(LocalTitleCardShape provides "poster") {
                            SaabTvCard(
                                title = "Poster Movie", posterUrl = null, normalWidth = 120.dp,
                                normalHeight = 180.dp,
                                previewItem = MetaItem("movie-poster", "movie", "Poster Movie"),
                                onClick = {}
                            )
                        }
                        CompositionLocalProvider(LocalTitleCardShape provides "poster") {
                            SaabTvCard(
                                title = "Poster Series", posterUrl = null, normalWidth = 120.dp,
                                normalHeight = 180.dp,
                                previewItem = MetaItem("series-poster", "series", "Poster Series"),
                                onClick = {}
                            )
                        }
                    }
                    Row {
                        CompositionLocalProvider(LocalTitleCardShape provides "landscape") {
                            SaabTvCard(
                                title = "Landscape Movie", posterUrl = null,
                                previewItem = MetaItem("movie-landscape", "movie", "Landscape Movie"),
                                forceLandscape = true, landscapeWidth = 190.dp, onClick = {}
                            )
                        }
                        CompositionLocalProvider(LocalTitleCardShape provides "landscape") {
                            SaabTvCard(
                                title = "Landscape Series", posterUrl = null,
                                previewItem = MetaItem("series-landscape", "series", "Landscape Series"),
                                forceLandscape = true, landscapeWidth = 190.dp, onClick = {}
                            )
                        }
                    }
                }
            }
        }

        compose.onNodeWithContentDescription("Poster Movie").assertHeightIsEqualTo(180.dp)
        compose.onNodeWithContentDescription("Poster Series").assertHeightIsEqualTo(180.dp)
        compose.onNodeWithContentDescription("Landscape Movie").assertHeightIsEqualTo(106.875.dp)
        compose.onNodeWithContentDescription("Landscape Series").assertHeightIsEqualTo(106.875.dp)
    }

    @Test fun posterAndLandscapeCardsPublishLiveWatchedAndWatchlistChanges() {
        val watchedIds = androidx.compose.runtime.mutableStateOf(emptySet<String>())
        val watchlistIds = androidx.compose.runtime.mutableStateOf(emptySet<String>())
        val landscape = androidx.compose.runtime.mutableStateOf(false)

        compose.setContent {
            androidx.tv.material3.MaterialTheme {
                CompositionLocalProvider(
                    LocalWatchedIds provides watchedIds.value,
                    LocalWatchlistIds provides watchlistIds.value
                ) {
                    SaabTvCard(
                        title = "Live State Title",
                        posterUrl = null,
                        forceLandscape = landscape.value,
                        previewItem = MetaItem("live-state", "movie", "Live State Title"),
                        onClick = {}
                    )
                }
            }
        }

        listOf(false, true).forEach { useLandscape ->
            compose.runOnIdle { landscape.value = useLandscape }
            compose.waitForIdle()

            compose.onNodeWithContentDescription("Live State Title").assertExists()
            compose.onNodeWithContentDescription("Watched").assertDoesNotExist()
            compose.onNodeWithContentDescription("Added to My Library").assertDoesNotExist()

            compose.runOnIdle { watchlistIds.value = setOf("live-state") }
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Added to My Library").assertExists()
            compose.onNodeWithContentDescription("Live State Title")
                .assert(SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Added to My Library"
                ))

            // Marking watched removes the library state and updates both card renderers.
            compose.runOnIdle {
                watchedIds.value = setOf("live-state")
                watchlistIds.value = emptySet()
            }
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Watched").assertExists()
            compose.onNodeWithContentDescription("Added to My Library").assertDoesNotExist()
            compose.onNodeWithContentDescription("Live State Title")
                .assert(SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Watched"
                ))

            compose.runOnIdle { watchedIds.value = emptySet() }
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Watched").assertDoesNotExist()
            compose.onNodeWithContentDescription("Live State Title").assertExists()
        }
    }

    @Test fun missingPosterUsesTitleInitialsAndShowsWatchlistMarker() {
        compose.setContent {
            androidx.tv.material3.MaterialTheme {
                CompositionLocalProvider(LocalWatchlistIds provides setOf("tt-title")) {
                    SaabTvCard(
                        title = "North Shore", posterUrl = null,
                        previewItem = MetaItem(id = "tt-title", type = "movie", name = "North Shore"),
                        onClick = {}
                    )
                }
            }
        }

        compose.onNodeWithText("NS").assertExists()
        compose.onNodeWithContentDescription("Added to My Library").assertExists()
    }

    @Test fun landscapePreferenceKeepsSeriesStackCardAndWatchlistMarker() {
        compose.setContent {
            androidx.tv.material3.MaterialTheme {
                CompositionLocalProvider(
                    LocalTitleCardShape provides "landscape",
                    LocalWatchlistIds provides setOf("series-id")
                ) {
                    SaabTvCard(
                        title = "North Shore", posterUrl = null,
                        previewItem = MetaItem(id = "series-id", type = "series", name = "North Shore"),
                        onClick = {}
                    )
                }
            }
        }

        compose.onNodeWithText("NS").assertExists()
        compose.onNodeWithContentDescription("Added to My Library").assertExists()
    }

    @Test fun landscapeRecommendationFallsBackToInitialsWhenOnlyPortraitPosterExists() {
        compose.setContent {
            androidx.tv.material3.MaterialTheme {
                CompositionLocalProvider(LocalTitleCardShape provides "landscape") {
                    SaabTvCard(
                        title = "North Shore", posterUrl = "https://example.test/poster.jpg",
                        previewItem = MetaItem(
                            id = "tmdb:123", type = "movie", name = "North Shore",
                            poster = "https://example.test/poster.jpg", background = null
                        ),
                        onClick = {}
                    )
                }
            }
        }

        compose.onNodeWithText("NS").assertExists()
    }

    @Test fun watchedTmdbCardRendersTheWatchedTickFromItsResolvedAlias() {
        compose.setContent {
            androidx.tv.material3.MaterialTheme {
                CompositionLocalProvider(LocalWatchedIds provides setOf("tmdb:987")) {
                    SaabTvCard(
                        title = "Alias Target", posterUrl = null,
                        previewItem = MetaItem(id = "tmdb:987", type = "movie", name = "Alias Target"),
                        onClick = {}
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("Watched").assertExists()
    }

    @Test fun watchedBadgeIsAccessibleInPosterAndLandscapeCards() {
        compose.setContent {
            androidx.tv.material3.MaterialTheme {
                CompositionLocalProvider(LocalWatchedIds provides setOf("watched")) {
                    Row {
                        SaabTvCard(
                            title = "Watched Poster", posterUrl = null,
                            previewItem = MetaItem("watched", "movie", "Watched Poster"), onClick = {}
                        )
                        SaabTvCard(
                            title = "Watched Landscape", posterUrl = null, forceLandscape = true,
                            previewItem = MetaItem("watched", "movie", "Watched Landscape"), onClick = {}
                        )
                    }
                }
            }
        }

        compose.onAllNodesWithContentDescription("Watched").assertCountEquals(2)
    }

    @Test fun posterAndLandscapeCardsRenderMatchingWatchedAndWatchlistBadges() {
        compose.setContent {
            androidx.tv.material3.MaterialTheme {
                androidx.compose.foundation.layout.Column {
                    listOf(false to false, false to true, true to false, true to true)
                        .forEachIndexed { index, (watched, watchlisted) ->
                            val id = "state-$index"
                            val item = MetaItem(id = id, type = "movie", name = "Shared State")
                            CompositionLocalProvider(
                                LocalWatchedIds provides if (watched) setOf(id) else emptySet(),
                                LocalWatchlistIds provides if (watchlisted) setOf(id) else emptySet()
                            ) {
                                Row {
                                    SaabTvCard(title = "Shared State", posterUrl = null, previewItem = item, onClick = {})
                                    SaabTvCard(
                                        title = "Shared State", posterUrl = null, previewItem = item,
                                        forceLandscape = true, onClick = {}
                                    )
                                }
                            }
                    }
                }
            }
        }

        // Of four states, two are watched and two are watchlisted; each renders in both shapes.
        compose.onAllNodesWithContentDescription("Watched").assertCountEquals(4)
        compose.onAllNodesWithContentDescription("Added to My Library").assertCountEquals(4)
    }

    @Test fun continueWatchingCanHideWatchedTickWithoutHidingWatchlistBadge() {
        compose.setContent {
            androidx.tv.material3.MaterialTheme {
                CompositionLocalProvider(
                    LocalWatchedIds provides setOf("title"),
                    LocalWatchlistIds provides setOf("title")
                ) {
                    SaabTvCard(
                        title = "Continue Title", posterUrl = null,
                        previewItem = MetaItem(id = "title", type = "series", name = "Continue Title"),
                        enableWatchedBadge = false,
                        onClick = {}
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("Watched").assertDoesNotExist()
        compose.onNodeWithContentDescription("Added to My Library").assertExists()
    }

    @Test fun titleCardAnnouncesWatchedAndLibraryStateWithoutChangingItsTitleLabel() {
        compose.setContent {
            androidx.tv.material3.MaterialTheme {
                CompositionLocalProvider(
                    LocalWatchedIds provides setOf("title"),
                    LocalWatchlistIds provides setOf("title")
                ) {
                    SaabTvCard(
                        title = "Accessible Title", posterUrl = null,
                        previewItem = MetaItem(id = "title", type = "movie", name = "Accessible Title"),
                        onClick = {}
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("Accessible Title")
            .assert(SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                "Watched, Added to My Library"
            ))
    }

    @Test fun continueWatchingStateAnnouncementRespectsItsHiddenWatchedBadge() {
        compose.setContent {
            androidx.tv.material3.MaterialTheme {
                CompositionLocalProvider(
                    LocalWatchedIds provides setOf("title"),
                    LocalWatchlistIds provides setOf("title")
                ) {
                    SaabTvCard(
                        title = "Continue Title", posterUrl = null,
                        previewItem = MetaItem(id = "title", type = "series", name = "Continue Title"),
                        enableWatchedBadge = false,
                        onClick = {}
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("Continue Title")
            .assert(SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                "Added to My Library"
            ))
    }

    @Test fun viewMoreCardUsesLandscapeAspectRatioForLandscapeRails() {
        compose.setContent {
            androidx.tv.material3.MaterialTheme {
                ViewMoreCard(
                    onClick = {}, cardWidth = 220.dp, isLandscape = true,
                    modifier = Modifier.testTag("landscape-view-more")
                )
            }
        }

        val bounds = compose.onNodeWithTag("landscape-view-more").fetchSemanticsNode().boundsInRoot
        assertEquals(16f / 9f, bounds.width / bounds.height, 0.02f)
    }

    @Test fun posterAndLandscapeCardsKeepTheirGeometryWhenFocused() {
        compose.setContent {
            androidx.tv.material3.MaterialTheme {
                Row {
                    SaabTvCard(title = "Poster", posterUrl = null, onClick = {})
                    SaabTvCard(
                        title = "Landscape", posterUrl = null, forceLandscape = true,
                        onClick = {}
                    )
                }
            }
        }

        val cards = compose.onAllNodes(hasClickAction())
        val poster = cards[0]
        val landscape = cards[1]
        val posterBounds = poster.fetchSemanticsNode().boundsInRoot
        val landscapeBounds = landscape.fetchSemanticsNode().boundsInRoot

        poster.performSemanticsAction(SemanticsActions.RequestFocus)
        poster.assertIsFocused()
        assertEquals(posterBounds, poster.fetchSemanticsNode().boundsInRoot)

        landscape.performSemanticsAction(SemanticsActions.RequestFocus)
        landscape.assertIsFocused()
        assertEquals(landscapeBounds, landscape.fetchSemanticsNode().boundsInRoot)
    }

    @Test fun viewMoreCardKeepsItsGeometryWhenFocused() {
        compose.setContent {
            androidx.tv.material3.MaterialTheme {
                ViewMoreCard(onClick = {}, modifier = Modifier.testTag("view-more-focus"))
            }
        }

        val card = compose.onNodeWithTag("view-more-focus")
        val bounds = card.fetchSemanticsNode().boundsInRoot
        compose.onNode(hasClickAction()).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNode(hasClickAction()).assertIsFocused()
        assertEquals(bounds, card.fetchSemanticsNode().boundsInRoot)
    }
}
