package com.saab.tv.ui.cast

import android.app.Application
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.input.key.Key
import androidx.lifecycle.viewModelScope
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.domain.schema.toCanonicalProjection
import com.saab.tv.testing.FeatureFixture
import com.saab.tv.ui.components.ArtworkPrefetchDispatcher
import com.saab.tv.ui.components.ArtworkPrefetchRequest
import com.saab.tv.ui.components.ArtworkPrefetchRole
import com.saab.tv.ui.components.LocalTitleCardShape
import com.saab.tv.ui.components.ShelfFocusIndicatorActive
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
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
class CastFilmographyArtworkComposeTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var fixture: FeatureFixture
    private lateinit var homeViewModel: com.saab.tv.ui.home.HomeViewModel

    @Before fun setUp() = runBlocking {
        fixture = FeatureFixture(RuntimeEnvironment.getApplication())
        fixture.dao.insertProfile(ProfileEntity(id = 94, name = "Filmography", isActive = true))
        fixture.app.configuration.saveRuntimeState(94)
        homeViewModel = fixture.home()
    }

    @After fun tearDown() {
        homeViewModel.viewModelScope.cancel()
        fixture.close()
    }

    @Test fun filmographyPrefetchesVisiblePosterArtwork() {
        val requests = CopyOnWriteArrayList<ArtworkPrefetchRequest>()
        render(
            item = MetaItem("tmdb:88001", "movie", "Poster", poster = "filmography-poster-url")
                .toCanonicalProjection()!!,
            shape = "poster",
            dispatcher = recordingDispatcher(requests)
        )

        compose.waitUntil(5_000) {
            requests.any { it.role == ArtworkPrefetchRole.Poster && it.url == "filmography-poster-url" }
        }
    }

    @Test fun filmographyPrefetchesLandscapeAndLogoForLandscapeCards() {
        val requests = CopyOnWriteArrayList<ArtworkPrefetchRequest>()
        render(
            item = MetaItem(
                "tmdb:88002", "series", "Landscape",
                background = "filmography-backdrop-url", logo = "filmography-logo-url"
            ).toCanonicalProjection()!!,
            shape = "landscape",
            dispatcher = recordingDispatcher(requests)
        )

        compose.waitUntil(5_000) {
            requests.any { it.role == ArtworkPrefetchRole.Landscape && it.url == "filmography-backdrop-url" } &&
                requests.any { it.role == ArtworkPrefetchRole.Logo && it.url == "filmography-logo-url" }
        }
        compose.onNodeWithTag("filmography-focus-indicator")
            .assertWidthIsEqualTo(190.dp)
            .assertHeightIsEqualTo(106.875.dp)
    }

    @Test fun filmographyUsesProductionPrefetcherWhenNoDispatcherIsInjected() {
        render(
            item = MetaItem("tmdb:88003", "movie", "No Artwork").toCanonicalProjection()!!,
            shape = "poster",
            dispatcher = null
        )
        compose.waitForIdle()
    }

    @Test fun focusedFilmographyCardCanOpenItsCanonicalTitle() {
        val requester = FocusRequester()
        val navigations = mutableListOf<Triple<String, String, Int>>()
        val exitFocus = FocusRequester()
        render(
            item = MetaItem("tmdb:88004", "movie", "Focusable", poster = "focusable-poster-url")
                .toCanonicalProjection()!!,
            shape = "poster",
            dispatcher = recordingDispatcher(CopyOnWriteArrayList()),
            initialFocusRequester = requester,
            exitFocusRequester = exitFocus,
            onNavigate = { type, id, index -> navigations += Triple(type, id, index) }
        )

        val indicator = compose.onNodeWithTag("filmography-focus-indicator")
        indicator.assertWidthIsEqualTo(120.dp)
        indicator.assertHeightIsEqualTo(180.dp)
        indicator.assertLeftPositionInRootIsEqualTo(48.dp)
        indicator.assert(SemanticsMatcher.expectValue(ShelfFocusIndicatorActive, false))
        compose.runOnIdle { requester.requestFocus() }
        compose.waitForIdle()
        indicator.assert(SemanticsMatcher.expectValue(ShelfFocusIndicatorActive, true))
        compose.runOnIdle { exitFocus.requestFocus() }
        compose.waitForIdle()
        indicator.assert(SemanticsMatcher.expectValue(ShelfFocusIndicatorActive, false))
        compose.runOnIdle { requester.requestFocus() }
        compose.waitForIdle()
        compose.onNode(hasClickAction()).assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(listOf(Triple("movie", "tmdb:88004", 0)), navigations)
    }

    private fun render(
        item: com.saab.tv.domain.schema.TitleCardProjection,
        shape: String,
        dispatcher: ArtworkPrefetchDispatcher?,
        initialFocusRequester: FocusRequester? = null,
        exitFocusRequester: FocusRequester? = null,
        onNavigate: (String, String, Int) -> Unit = { _, _, _ -> }
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalTitleCardShape provides shape) {
                MaterialTheme {
                    Column {
                        FilmographySection(
                            credits = listOf(item), accentColor = Color.Cyan, textColor = Color.White,
                            onNavigateToDetails = onNavigate, onQuickAction = { _, _ -> },
                            enrichmentViewModel = homeViewModel, artworkPrefetchDispatcher = dispatcher,
                            initialFocusRequester = initialFocusRequester
                        )
                        exitFocusRequester?.let { Box(Modifier.size(40.dp).focusRequester(it).focusable()) }
                    }
                }
            }
        }
    }

    private fun recordingDispatcher(requests: MutableList<ArtworkPrefetchRequest>) = ArtworkPrefetchDispatcher(
        poster = { requests += ArtworkPrefetchRequest(ArtworkPrefetchRole.Poster, it) },
        landscape = { requests += ArtworkPrefetchRequest(ArtworkPrefetchRole.Landscape, it) },
        logo = { requests += ArtworkPrefetchRequest(ArtworkPrefetchRole.Logo, it) }
    )
}
