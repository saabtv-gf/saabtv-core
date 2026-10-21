package com.saab.tv.ui.details

import android.app.Application
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.domain.schema.toCanonicalProjection
import com.saab.tv.testing.FeatureFixture
import com.saab.tv.ui.trailer.TitleTrailerHost
import com.saab.tv.ui.components.ArtworkPrefetchDispatcher
import com.saab.tv.ui.components.ArtworkPrefetchRequest
import com.saab.tv.ui.components.ArtworkPrefetchRole
import com.saab.tv.ui.components.LocalTitleCardShape
import com.saab.tv.ui.components.ShelfFocusIndicatorActive
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land")
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class DetailsRecommendationArtworkComposeTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var fixture: FeatureFixture
    private lateinit var homeViewModel: com.saab.tv.ui.home.HomeViewModel

    @Before fun setUp() = runBlocking {
        fixture = FeatureFixture(RuntimeEnvironment.getApplication())
        fixture.dao.insertProfile(ProfileEntity(id = 93, name = "Recommendations", isActive = true))
        fixture.app.configuration.saveRuntimeState(93)
        homeViewModel = fixture.home()
    }

    @After fun tearDown() {
        homeViewModel.viewModelScope.cancel()
        fixture.close()
    }

    @Test fun recommendationRowFeedsVisibleCanonicalArtworkToTheSharedPrefetcher() {
        val projection = requireNotNull(
            MetaItem("tmdb:901", "movie", "Recommendation", poster = "recommendation-poster")
                .toCanonicalProjection()
        )
        val requests = CopyOnWriteArrayList<ArtworkPrefetchRequest>()
        val dispatcher = ArtworkPrefetchDispatcher(
            poster = { requests += ArtworkPrefetchRequest(ArtworkPrefetchRole.Poster, it) },
            landscape = { requests += ArtworkPrefetchRequest(ArtworkPrefetchRole.Landscape, it) },
            logo = { requests += ArtworkPrefetchRequest(ArtworkPrefetchRole.Logo, it) }
        )

        compose.setContent {
            MaterialTheme {
                RecommendationRow(
                    items = listOf(projection), accentColor = Color.Cyan, rowKey = "tmdb_recs",
                    profileId = 93, watchedViewModel = homeViewModel,
                    artworkPrefetchDispatcher = dispatcher
                )
            }
        }

        compose.waitUntil(5_000) {
            requests.any { it.role == ArtworkPrefetchRole.Poster && it.url == "recommendation-poster" }
        }
        assertTrue(requests.isNotEmpty())
    }

    @Test fun recommendationRowUsesProductionDispatcherWhenArtworkIsMissing() {
        val projection = requireNotNull(
            MetaItem("tmdb:902", "movie", "No Recommendation Artwork").toCanonicalProjection()
        )

        compose.setContent {
            MaterialTheme {
                RecommendationRow(
                    items = listOf(projection), accentColor = Color.Cyan, rowKey = "tmdb_empty_art",
                    profileId = 93, watchedViewModel = homeViewModel
                )
            }
        }

        compose.waitForIdle()
    }

    @Test fun recommendationCardReadsWatchedStateFromTheSharedLiveProfileFlow() {
        val item = MetaItem("tt900001", "movie", "Shared Recommendation")
        val projection = requireNotNull(item.toCanonicalProjection())

        compose.setContent {
            MaterialTheme {
                TitleTrailerHost(
                    onOpen = {},
                    defaultEnabled = false,
                    model = homeViewModel
                ) {
                    RecommendationRow(
                        items = listOf(projection),
                        accentColor = Color.Cyan,
                        rowKey = "watched-related-title",
                        profileId = 93,
                        watchedViewModel = homeViewModel,
                        artworkPrefetchDispatcher = ArtworkPrefetchDispatcher({}, {}, {})
                    )
                }
            }
        }

        val card = compose.onNodeWithContentDescription("Shared Recommendation")
        card.assertExists()
        runBlocking { homeViewModel.markTitleWatched(93, item).join() }
        compose.waitUntil(5_000) {
            val config = card.fetchSemanticsNode().config
            config.contains(SemanticsProperties.StateDescription) &&
                config[SemanticsProperties.StateDescription] == "Watched"
        }
        card.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Watched"))
    }

    @Test fun rightAndCenterOnRelatedCardCarryThatCardsOwnTypeIdAndLocation() {
        val first = requireNotNull(
            MetaItem("tmdb:770076", "movie", "Related Movie")
                .toCanonicalProjection()
        )
        val selectedProjection = requireNotNull(
            MetaItem("tmdb:770077", "series", "Related Series")
                .toCanonicalProjection()
        )
        data class ClickedTitle(val type: String, val id: String, val rowKey: String, val itemKey: String, val index: Int)
        var selected: ClickedTitle? = null

        compose.setContent {
            MaterialTheme {
                RecommendationRow(
                    items = listOf(first, selectedProjection),
                    accentColor = Color.Cyan,
                    rowKey = "related-series",
                    profileId = 93,
                    watchedViewModel = homeViewModel,
                    onItemClick = { type, id, rowKey, itemKey, index ->
                        selected = ClickedTitle(type, id, rowKey, itemKey, index)
                    },
                    artworkPrefetchDispatcher = ArtworkPrefetchDispatcher({}, {}, {})
                )
            }
        }

        compose.onNodeWithContentDescription("Related Movie")
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput {
                pressKey(Key.DirectionRight)
                pressKey(Key.DirectionCenter)
            }
        compose.runOnIdle {
            assertEquals(
                ClickedTitle("series", "tmdb:770077", "related-series", selectedProjection.presentationKey, 1),
                selected
            )
        }
    }

    @Test fun recommendationFocusFrameStaysPinnedAndClearsWhenFocusLeaves() {
        val cardFocus = FocusRequester()
        val outsideFocus = FocusRequester()
        val projection = requireNotNull(
            MetaItem("tmdb:903", "movie", "Focus Recommendation", poster = "focus-poster")
                .toCanonicalProjection()
        )

        compose.setContent {
            MaterialTheme {
                Column {
                    RecommendationRow(
                        items = listOf(projection), accentColor = Color.Cyan, rowKey = "tmdb_focus_poster",
                        profileId = 93, watchedViewModel = homeViewModel,
                        restoreIndex = 0, restoreFocusRequester = cardFocus,
                        artworkPrefetchDispatcher = ArtworkPrefetchDispatcher({}, {}, {})
                    )
                    Box(Modifier.size(40.dp).focusRequester(outsideFocus).focusable())
                }
            }
        }

        val indicator = compose.onNodeWithTag("details-recommendation-focus-indicator")
        indicator.assertWidthIsEqualTo(120.dp)
        indicator.assertHeightIsEqualTo(180.dp)
        indicator.assertLeftPositionInRootIsEqualTo(48.dp)
        indicator.assert(SemanticsMatcher.expectValue(ShelfFocusIndicatorActive, false))

        compose.runOnIdle { cardFocus.requestFocus() }
        compose.waitForIdle()
        indicator.assert(SemanticsMatcher.expectValue(ShelfFocusIndicatorActive, true))

        compose.runOnIdle { outsideFocus.requestFocus() }
        compose.waitForIdle()
        indicator.assert(SemanticsMatcher.expectValue(ShelfFocusIndicatorActive, false))
    }

    @Test fun recommendationFocusFrameMatchesLandscapeCardGeometry() {
        val projection = requireNotNull(
            MetaItem("tmdb:904", "series", "Landscape Focus Recommendation", background = "focus-backdrop")
                .toCanonicalProjection()
        )
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalTitleCardShape provides "landscape") {
                    RecommendationRow(
                        items = listOf(projection), accentColor = Color.Cyan, rowKey = "tmdb_focus_landscape",
                        profileId = 93, watchedViewModel = homeViewModel,
                        artworkPrefetchDispatcher = ArtworkPrefetchDispatcher({}, {}, {})
                    )
                }
            }
        }

        compose.onNodeWithTag("details-recommendation-focus-indicator")
            .assertWidthIsEqualTo(190.dp)
            .assertHeightIsEqualTo(106.875.dp)
    }
}
