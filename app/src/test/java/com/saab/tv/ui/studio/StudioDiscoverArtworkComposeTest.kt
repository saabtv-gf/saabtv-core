package com.saab.tv.ui.studio

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.*
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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.input.key.Key
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.data.tmdb.TmdbDiscoverRail
import com.saab.tv.data.localization.AppLanguage
import com.saab.tv.domain.schema.toCanonicalProjection
import com.saab.tv.domain.schema.canonicalPresentationKey
import com.saab.tv.ui.components.ArtworkPrefetchDispatcher
import com.saab.tv.ui.components.ArtworkPrefetchRequest
import com.saab.tv.ui.components.ArtworkPrefetchRole
import com.saab.tv.ui.components.LocalTitleCardShape
import com.saab.tv.ui.components.ShelfFocusIndicatorActive
import com.saab.tv.R
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land")
@OptIn(ExperimentalTestApi::class)
class StudioDiscoverArtworkComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun discoverRailPrefetchesVisiblePosterArtwork() {
        val requests = CopyOnWriteArrayList<ArtworkPrefetchRequest>()
        render(
            item = MetaItem("tmdb:88101", "movie", "Poster", poster = "studio-poster-url")
                .toCanonicalProjection()!!,
            shape = "poster",
            dispatcher = recordingDispatcher(requests)
        )

        compose.waitUntil(5_000) {
            requests.any { it.role == ArtworkPrefetchRole.Poster && it.url == "studio-poster-url" }
        }
        assertTrue(requests.isNotEmpty())
    }

    @Test fun discoverRailPrefetchesLandscapeAndLogoForLandscapeCards() {
        val requests = CopyOnWriteArrayList<ArtworkPrefetchRequest>()
        render(
            item = MetaItem(
                "tmdb:88102", "series", "Landscape",
                background = "studio-backdrop-url", logo = "studio-logo-url"
            ).toCanonicalProjection()!!,
            shape = "landscape",
            dispatcher = recordingDispatcher(requests)
        )

        compose.waitUntil(5_000) {
            requests.any { it.role == ArtworkPrefetchRole.Landscape && it.url == "studio-backdrop-url" } &&
                requests.any { it.role == ArtworkPrefetchRole.Logo && it.url == "studio-logo-url" }
        }
        compose.onNodeWithTag("studio-discover-focus-indicator")
            .assertWidthIsEqualTo(190.dp)
            .assertHeightIsEqualTo(106.875.dp)
    }

    @Test fun landscapeDiscoverRailUsesEnrichedTmdbBackdropAndLogoBeforeRenderingCards() {
        val source = MetaItem(
            "tmdb:88105", "movie", "Source title",
            background = "source-backdrop", logo = "source-logo"
        )
        val enriched = source.copy(background = "tmdb-backdrop", logo = "tmdb-logo")
        val requests = CopyOnWriteArrayList<ArtworkPrefetchRequest>()
        val prepared = CopyOnWriteArrayList<String>()

        render(
            item = source.toCanonicalProjection()!!,
            shape = "landscape",
            dispatcher = recordingDispatcher(requests),
            enrichedItems = mapOf(source.canonicalPresentationKey() to enriched),
            onPrepareItem = { prepared += it.id }
        )

        compose.waitUntil(5_000) {
            requests.any { it.role == ArtworkPrefetchRole.Landscape && it.url == "tmdb-backdrop" } &&
                requests.any { it.role == ArtworkPrefetchRole.Logo && it.url == "tmdb-logo" }
        }
        assertTrue("visible TMDB item must enter the enrichment pipeline", prepared.contains(source.id))
        assertTrue(requests.none { it.url == "source-backdrop" || it.url == "source-logo" })
    }

    @Test fun discoverRailUsesProductionPrefetcherWhenNoDispatcherIsInjected() {
        render(
            item = MetaItem("tmdb:88103", "movie", "No Artwork").toCanonicalProjection()!!,
            shape = "poster",
            dispatcher = null
        )
        compose.waitForIdle()
    }

    @Test fun nearEndPaginationObservesLayoutOutsideCompositionAndRequestsMore() {
        val loads = AtomicInteger()
        render(
            item = MetaItem("tmdb:88107", "movie", "Paginated", poster = "page-one")
                .toCanonicalProjection()!!,
            shape = "poster",
            dispatcher = recordingDispatcher(CopyOnWriteArrayList()),
            hasMore = true,
            onLoadMore = { loads.incrementAndGet() }
        )

        compose.waitUntil(5_000) { loads.get() >= 1 }
        compose.waitForIdle()
        assertEquals(1, loads.get())
    }

    @Test fun focusedDiscoverCardCanOpenItsCanonicalTitle() {
        val requester = FocusRequester()
        val navigations = mutableListOf<Triple<String, String, Int>>()
        render(
            item = MetaItem("tmdb:88104", "series", "Focusable", poster = "focusable-poster-url")
                .toCanonicalProjection()!!,
            shape = "poster",
            dispatcher = recordingDispatcher(CopyOnWriteArrayList()),
            initialFocusRequester = requester,
            onNavigate = { type, id, index -> navigations += Triple(type, id, index) }
        )

        val indicator = compose.onNodeWithTag("studio-discover-focus-indicator")
        indicator.assertWidthIsEqualTo(120.dp)
        indicator.assertHeightIsEqualTo(180.dp)
        indicator.assertLeftPositionInRootIsEqualTo(48.dp)
        compose.onNodeWithText("Series · Popular").assertExists()
        indicator.assert(SemanticsMatcher.expectValue(ShelfFocusIndicatorActive, false))
        compose.runOnIdle { requester.requestFocus() }
        compose.waitForIdle()
        indicator.assert(SemanticsMatcher.expectValue(ShelfFocusIndicatorActive, true))
        compose.onNode(hasClickAction()).assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(listOf(Triple("series", "tmdb:88104", 0)), navigations)
    }

    @Test fun longPressOnDiscoverCardRoutesQuickActionToThatTitle() {
        val requester = FocusRequester()
        val actions = mutableListOf<Pair<String, androidx.compose.ui.geometry.Rect>>()
        render(
            item = MetaItem("tmdb:88106", "movie", "Quick Action", poster = "quick-action-poster")
                .toCanonicalProjection()!!,
            shape = "poster",
            dispatcher = recordingDispatcher(CopyOnWriteArrayList()),
            initialFocusRequester = requester,
            onQuickAction = { item, bounds -> actions += item.id to bounds }
        )

        compose.runOnIdle { requester.requestFocus() }
        compose.onNode(hasClickAction()).performKeyInput {
            keyDown(Key.DirectionCenter)
            advanceEventTime(600)
            keyUp(Key.DirectionCenter)
        }
        compose.waitUntil(3_000) { actions.isNotEmpty() }

        assertEquals("tmdb:88106", actions.single().first)
        assertTrue(actions.single().second.width > 0f)
    }

    @Test fun studioLabelsAreLocalizedForKannadaAndMalayalam() {
        val base = RuntimeEnvironment.getApplication()
        val keys = listOf(
            R.string.studio_entity_network,
            R.string.studio_entity_production_company,
            R.string.studio_rail_popular,
            R.string.studio_rail_top_rated,
            R.string.studio_rail_recent
        )
        listOf("kn", "ml").forEach { language ->
            val localized = AppLanguage.localizedContext(base, language)
            keys.forEach { key ->
                assertNotEquals("Resource $key should be localized for $language", base.getString(key), localized.getString(key))
            }
        }
    }

    private fun render(
        item: com.saab.tv.domain.schema.TitleCardProjection,
        shape: String,
        dispatcher: ArtworkPrefetchDispatcher?,
        hasMore: Boolean = false,
        onLoadMore: () -> Unit = {},
        enrichedItems: Map<String, MetaItem> = emptyMap(),
        onPrepareItem: (MetaItem) -> Unit = {},
        initialFocusRequester: FocusRequester? = null,
        onNavigate: (String, String, Int) -> Unit = { _, _, _ -> },
        onQuickAction: (MetaItem, androidx.compose.ui.geometry.Rect) -> Unit = { _, _ -> }
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalTitleCardShape provides shape) {
                MaterialTheme {
                    DiscoverRailSection(
                        rail = TmdbDiscoverRail(
                            mediaType = if (item.kind.keyPart == "series") "tv" else "movie",
                            railType = "popular",
                            items = listOf(item),
                            hasMore = hasMore
                        ),
                        accentColor = Color.Cyan, textColor = Color.White,
                        onItemClick = onNavigate, onLoadMore = onLoadMore,
                        initialFocusRequester = initialFocusRequester,
                        artworkPrefetchDispatcher = dispatcher,
                        enrichedItems = enrichedItems,
                        onPrepareItem = onPrepareItem,
                        onQuickAction = onQuickAction
                    )
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
