package com.saab.tv.ui.navigation

import android.os.SystemClock
import android.view.KeyEvent as AndroidKeyEvent
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.testfixture.ComposeTestActivity
import com.saab.tv.ui.components.ArtworkPrefetchDispatcher
import com.saab.tv.ui.components.SetupButton
import com.saab.tv.ui.home.DpadRepeatGate
import com.saab.tv.ui.home.InfiniteLoopRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Android D-pad journeys for the navigation focus-entry contract. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class NavigationShellFocusTvJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestActivity>()

    private val requesters = NavDestination.entries.associateWith { FocusRequester() }
    private val contentRequester = FocusRequester()
    private val selected = mutableListOf<NavDestination>()
    private var contentReturns = 0

    private fun render() {
        compose.setContent {
            MaterialTheme {
                NavDrawer(
                    currentDestination = NavDestination.Home,
                    currentProfile = null,
                    drawerRequesters = requesters,
                    onNavigate = { selected += it; contentRequester.requestFocus() },
                    onClose = { contentReturns++; contentRequester.requestFocus() }
                ) {
                    val authorizeHandoff = LocalNavigationFocusHandoff.current
                    Box(Modifier.padding(start = 250.dp, top = 50.dp)) {
                        SetupButton(
                            text = "Browse content",
                            onClick = {},
                            focusRequester = contentRequester,
                            modifier = Modifier.onPreviewKeyEvent { event ->
                                if (event.key == Key.DirectionLeft) {
                                    handoffNavigationFocus(event, authorizeHandoff)
                                } else false
                            }
                        )
                    }
                }
            }
        }
        requestNodeFocus("Browse content", contentRequester)
    }

    @Test fun directionalEntryAndSelectionReturnToContent() {
        render()
        compose.onNodeWithText("Browse content").performKeyInput { pressKey(Key.DirectionLeft) }
        assertNodeFocused("Home")
        compose.onNodeWithText("Home").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Movies").performKeyInput { pressKey(Key.DirectionCenter) }

        assertNodeFocused("Browse content")
        compose.runOnIdle { assertEquals(listOf(NavDestination.Movies), selected) }
    }

    @Test fun everyDrawerDestinationCanBeReachedAndSelectedWithDpad() {
        val navigated = mutableListOf<NavDestination>()
        compose.setContent {
            MaterialTheme {
                NavDrawer(
                    currentDestination = NavDestination.Home,
                    currentProfile = null,
                    drawerRequesters = requesters,
                    onNavigate = { navigated += it },
                    onClose = {}
                ) {
                    val authorizeHandoff = LocalNavigationFocusHandoff.current
                    Box(Modifier.padding(start = 250.dp, top = 50.dp)) {
                        SetupButton(
                            text = "Browse every route",
                            onClick = {},
                            focusRequester = contentRequester,
                            modifier = Modifier.onPreviewKeyEvent { event ->
                                if (event.key == Key.DirectionLeft) {
                                    handoffNavigationFocus(event, authorizeHandoff)
                                } else false
                            }
                        )
                    }
                }
            }
        }
        requestNodeFocus("Browse every route", contentRequester)

        compose.onNodeWithText("Browse every route").performKeyInput { pressKey(Key.DirectionLeft) }
        assertNodeFocused("Home")
        compose.onNodeWithText("Home").performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithText("Search").performKeyInput { pressKey(Key.DirectionUp) }

        val label = mapOf(
            NavDestination.Profile to "Profile",
            NavDestination.Search to "Search",
            NavDestination.Home to "Home",
            NavDestination.Movies to "Movies",
            NavDestination.Series to "Series",
            NavDestination.Ott to "OTT",
            NavDestination.Watchlist to "My Library",
            NavDestination.Settings to "Settings",
            NavDestination.Exit to "Exit"
        )
        DrawerFocusPolicy.order.forEachIndexed { index, destination ->
            val item = compose.onNodeWithText(label.getValue(destination))
            assertNodeFocused(label.getValue(destination))
            item.performKeyInput { pressKey(Key.DirectionCenter) }
            compose.runOnIdle {
                assertEquals(
                    "D-pad center should select $destination",
                    DrawerFocusPolicy.order.take(index + 1),
                    navigated
                )
            }
            if (index < DrawerFocusPolicy.order.lastIndex) {
                item.performKeyInput { pressKey(Key.DirectionDown) }
            }
        }
    }

    @Test fun consumedLeftBoundaryPressIsAuthorizedToEnterSideNavigation() {
        val rowEntry = FocusRequester()
        val boundaryTitle = MetaItem("boundary-title", "movie", "Boundary title")
        compose.setContent {
            MaterialTheme {
                NavDrawer(
                    currentDestination = NavDestination.Home,
                    currentProfile = null,
                    drawerRequesters = requesters,
                    onNavigate = { selected += it },
                    onClose = {}
                ) {
                    TvMaterialTheme {
                        InfiniteLoopRow(
                            startPadding = 48.dp,
                            isTopNav = false,
                            rowIndex = 0,
                            title = "Boundary shelf",
                            items = listOf(boundaryTitle),
                            onMovieClick = {},
                            onViewMore = {},
                            onFocused = { _, _ -> },
                            entryRequester = rowEntry,
                            drawerRequester = requesters.getValue(NavDestination.Home),
                            locallyFocusedItemId = null,
                            isGlobalFocusPresent = true,
                            isFirstRow = true,
                            isInfiniteLoopEnabled = false,
                            repeatGate = remember { DpadRepeatGate() },
                            artworkPrefetchDispatcher = remember { ArtworkPrefetchDispatcher({}, {}, {}) }
                        )
                    }
                }
            }
        }

        val card = compose.onNode(hasClickAction() and hasContentDescription("Boundary title"))
        card.performSemanticsAction(SemanticsActions.RequestFocus)
        card.performKeyInput { pressKey(Key.DirectionLeft) }

        compose.onNodeWithText("Home").assertIsFocused()
    }

    @Test fun siblingScreenCanAuthorizeBoundaryEntryIntoSideNavigation() {
        val rowEntry = FocusRequester()
        val fallbackRequester = FocusRequester()
        val boundaryTitle = MetaItem("sibling-boundary-title", "movie", "Sibling boundary title")
        compose.setContent {
            MaterialTheme {
                NavigationFocusHandoffHost {
                    Box(Modifier.fillMaxSize()) {
                        TvMaterialTheme {
                            InfiniteLoopRow(
                                startPadding = 48.dp,
                                isTopNav = false,
                                rowIndex = 0,
                                title = "Sibling shelf",
                                items = listOf(boundaryTitle),
                                onMovieClick = {},
                                onViewMore = {},
                                onFocused = { _, _ -> },
                                entryRequester = rowEntry,
                                drawerRequester = fallbackRequester,
                                locallyFocusedItemId = null,
                                isGlobalFocusPresent = true,
                                isFirstRow = true,
                                isInfiniteLoopEnabled = false,
                                repeatGate = remember { DpadRepeatGate() },
                                artworkPrefetchDispatcher = remember { ArtworkPrefetchDispatcher({}, {}, {}) }
                            )
                        }
                        SetupButton(
                            text = "Fallback focus target",
                            onClick = {},
                            focusRequester = fallbackRequester,
                            modifier = Modifier.padding(start = 400.dp, top = 500.dp)
                        )
                        NavDrawer(
                            currentDestination = NavDestination.Home,
                            currentProfile = null,
                            drawerRequesters = requesters,
                            onNavigate = { selected += it },
                            onClose = { contentReturns++ },
                            content = {}
                        )
                    }
                }
            }
        }

        val card = compose.onNode(hasClickAction() and hasContentDescription("Sibling boundary title"))
        card.performSemanticsAction(SemanticsActions.RequestFocus)
        card.performKeyInput { pressKey(Key.DirectionLeft) }

        compose.onNodeWithText("Home").assertIsFocused()
        compose.onNodeWithText("Fallback focus target").assertIsNotFocused()
    }

    @Test fun siblingScreenCanAuthorizeBoundaryEntryIntoTopNavigation() {
        val rowEntry = FocusRequester()
        val topRequesters = NavDestination.entries.associateWith { FocusRequester() }
        val boundaryTitle = MetaItem("sibling-top-title", "movie", "Sibling top title")
        compose.setContent {
            MaterialTheme {
                NavigationFocusHandoffHost {
                    Box(Modifier.fillMaxSize()) {
                        TvMaterialTheme {
                            InfiniteLoopRow(
                                startPadding = 48.dp,
                                isTopNav = true,
                                rowIndex = 0,
                                title = "Sibling top shelf",
                                items = listOf(boundaryTitle),
                                onMovieClick = {},
                                onViewMore = {},
                                onFocused = { _, _ -> },
                                entryRequester = rowEntry,
                                drawerRequester = topRequesters.getValue(NavDestination.Home),
                                locallyFocusedItemId = null,
                                isGlobalFocusPresent = true,
                                isFirstRow = true,
                                isInfiniteLoopEnabled = false,
                                repeatGate = remember { DpadRepeatGate() },
                                artworkPrefetchDispatcher = remember { ArtworkPrefetchDispatcher({}, {}, {}) }
                            )
                        }
                        TopNavigationBar(
                            currentDestination = NavDestination.Home,
                            currentProfile = null,
                            topNavRequesters = topRequesters,
                            onNavigate = { selected += it },
                            onEnterContent = {},
                            content = {}
                        )
                    }
                }
            }
        }

        val card = compose.onNode(hasClickAction() and hasContentDescription("Sibling top title"))
        card.performSemanticsAction(SemanticsActions.RequestFocus)
        card.performKeyInput { pressKey(Key.DirectionUp) }

        compose.onNodeWithContentDescription("Home").assertIsFocused()
    }

    @Test fun consumedUpBoundaryPressIsAuthorizedToEnterTopNavigation() {
        val rowEntry = FocusRequester()
        val topRequesters = NavDestination.entries.associateWith { FocusRequester() }
        val boundaryTitle = MetaItem("top-boundary-title", "movie", "Top boundary title")
        compose.setContent {
            MaterialTheme {
                TopNavigationBar(
                    currentDestination = NavDestination.Home,
                    currentProfile = null,
                    topNavRequesters = topRequesters,
                    onNavigate = { selected += it },
                    onEnterContent = {}
                ) {
                    TvMaterialTheme {
                        InfiniteLoopRow(
                            startPadding = 48.dp,
                            isTopNav = true,
                            rowIndex = 0,
                            title = "Top boundary shelf",
                            items = listOf(boundaryTitle),
                            onMovieClick = {},
                            onViewMore = {},
                            onFocused = { _, _ -> },
                            entryRequester = rowEntry,
                            drawerRequester = topRequesters.getValue(NavDestination.Home),
                            locallyFocusedItemId = null,
                            isGlobalFocusPresent = true,
                            isFirstRow = true,
                            isInfiniteLoopEnabled = false,
                            repeatGate = remember { DpadRepeatGate() },
                            artworkPrefetchDispatcher = remember { ArtworkPrefetchDispatcher({}, {}, {}) }
                        )
                    }
                }
            }
        }

        val card = compose.onNode(hasClickAction() and hasContentDescription("Top boundary title"))
        card.performSemanticsAction(SemanticsActions.RequestFocus)
        card.performKeyInput { pressKey(Key.DirectionUp) }

        compose.onNodeWithContentDescription("Home").assertIsFocused()
    }

    @Test fun leftAtTopNavigationShelfStartKeepsFocusInContent() {
        val rowEntry = FocusRequester()
        val topRequesters = NavDestination.entries.associateWith { FocusRequester() }
        val boundaryTitle = MetaItem("top-left-boundary-title", "movie", "Top left boundary title")
        compose.setContent {
            MaterialTheme {
                TopNavigationBar(
                    currentDestination = NavDestination.Home,
                    currentProfile = null,
                    topNavRequesters = topRequesters,
                    onNavigate = { selected += it },
                    onEnterContent = {}
                ) {
                    TvMaterialTheme {
                        InfiniteLoopRow(
                            startPadding = 48.dp,
                            isTopNav = true,
                            rowIndex = 0,
                            title = "Top navigation shelf",
                            items = listOf(boundaryTitle),
                            onMovieClick = {},
                            onViewMore = {},
                            onFocused = { _, _ -> },
                            entryRequester = rowEntry,
                            drawerRequester = topRequesters.getValue(NavDestination.Home),
                            locallyFocusedItemId = null,
                            isGlobalFocusPresent = true,
                            isFirstRow = true,
                            isInfiniteLoopEnabled = false,
                            repeatGate = remember { DpadRepeatGate() },
                            artworkPrefetchDispatcher = remember { ArtworkPrefetchDispatcher({}, {}, {}) }
                        )
                    }
                }
            }
        }

        val card = compose.onNode(hasClickAction() and hasContentDescription("Top left boundary title"))
        card.performSemanticsAction(SemanticsActions.RequestFocus)
        card.performKeyInput { pressKey(Key.DirectionLeft) }

        card.assertIsFocused()
        compose.onNodeWithContentDescription("Home").assertIsNotFocused()
    }

    @Test fun automaticFocusWithoutDirectionalEntryIsReturnedToContent() {
        render()
        compose.runOnIdle { requesters.getValue(NavDestination.Settings).requestFocus() }
        assertNodeFocused("Browse content")
        compose.runOnIdle {
            assertTrue("focus recovery must not navigate", selected.isEmpty())
            assertTrue("focus recovery should return to content", contentReturns > 0)
        }
    }

    @Test fun actionTriggeredFocusAttemptCannotSilentlyExpandDrawer() {
        var actionCalls = 0
        var contentReturns = 0
        compose.setContent {
            MaterialTheme {
                NavDrawer(
                    currentDestination = NavDestination.Home,
                    currentProfile = null,
                    drawerRequesters = requesters,
                    onNavigate = { selected += it },
                    onClose = { contentReturns++; contentRequester.requestFocus() }
                ) {
                    Box(Modifier.padding(start = 250.dp, top = 50.dp)) {
                        SetupButton(
                            text = "Complete action",
                            onClick = { actionCalls++; requesters.getValue(NavDestination.Home).requestFocus() },
                            focusRequester = contentRequester
                        )
                    }
                }
            }
        }
        requestNodeFocus("Complete action", contentRequester)

        compose.onNodeWithText("Complete action").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()

        assertNodeFocused("Complete action")
        compose.onNodeWithText("Home").assertIsNotFocused()
        compose.runOnIdle {
            assertEquals("the action should run exactly once", 1, actionCalls)
            assertEquals("automatic focus recovery must not navigate", emptyList<NavDestination>(), selected)
            assertTrue("rejected menu focus should restore content", contentReturns > 0)
        }
    }

    @Test fun contentHandledDirectionCannotAuthorizeLaterAutomaticDrawerFocus() {
        compose.setContent {
            MaterialTheme {
                NavDrawer(
                    currentDestination = NavDestination.Home,
                    currentProfile = null,
                    drawerRequesters = requesters,
                    onNavigate = { selected += it },
                    onClose = { contentReturns++; contentRequester.requestFocus() }
                ) {
                    Box(Modifier.padding(start = 250.dp, top = 50.dp)) {
                        SetupButton(
                            text = "Handled direction",
                            onClick = {},
                            focusRequester = contentRequester,
                            modifier = Modifier
                                .focusProperties { left = requesters.getValue(NavDestination.Home) }
                                .onPreviewKeyEvent { event ->
                                    event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp
                                }
                        )
                    }
                }
            }
        }
        requestNodeFocus("Handled direction", contentRequester)

        compose.onNodeWithText("Handled direction").performKeyInput { pressKey(Key.DirectionUp) }
        compose.runOnIdle { requesters.getValue(NavDestination.Home).requestFocus() }
        compose.waitForIdle()

        assertNodeFocused("Handled direction")
        compose.onNodeWithText("Home").assertIsNotFocused()
        compose.runOnIdle { assertTrue("handled input cannot navigate", selected.isEmpty()) }
    }

    @Test fun outgoingDrawerCannotCaptureFocusDuringNavigationLayoutCrossfade() {
        compose.setContent {
            MaterialTheme {
                NavDrawer(
                    currentDestination = NavDestination.Home,
                    currentProfile = null,
                    drawerRequesters = requesters,
                    onNavigate = { selected += it },
                    onClose = {},
                    navigationActive = false
                ) {
                    Box(Modifier.padding(start = 250.dp, top = 50.dp)) {
                        SetupButton(text = "Browse content", onClick = {}, focusRequester = contentRequester)
                    }
                }
            }
        }
        requestNodeFocus("Browse content", contentRequester)
        compose.runOnIdle { requesters.getValue(NavDestination.Home).requestFocus() }
        compose.waitForIdle()

        assertNodeFocused("Browse content")
        compose.onNodeWithText("Home").assertIsNotFocused()
    }

    @Test fun outgoingTopNavigationCannotCaptureFocusDuringNavigationLayoutCrossfade() {
        compose.setContent {
            MaterialTheme {
                TopNavigationBar(
                    currentDestination = NavDestination.Home,
                    currentProfile = null,
                    topNavRequesters = requesters,
                    onNavigate = { selected += it },
                    onEnterContent = {},
                    navigationActive = false
                ) {
                    Box(Modifier.padding(top = 100.dp)) {
                        SetupButton(text = "Browse content", onClick = {}, focusRequester = contentRequester)
                    }
                }
            }
        }
        requestNodeFocus("Browse content", contentRequester)
        compose.runOnIdle { requesters.getValue(NavDestination.Home).requestFocus() }
        compose.waitForIdle()

        assertNodeFocused("Browse content")
        compose.onNodeWithContentDescription("Home").assertIsNotFocused()
    }

    @Test fun heldRightAtDrawerBoundaryRequestsContentOnlyOnce() {
        var rootView: View? = null
        var handoffs = 0
        val content = FocusRequester()
        compose.setContent {
            rootView = LocalView.current
            MaterialTheme {
                NavDrawer(
                    currentDestination = NavDestination.Home,
                    currentProfile = null,
                    drawerRequesters = requesters,
                    onNavigate = {},
                    onClose = { handoffs++ }
                ) {
                    val authorizeHandoff = LocalNavigationFocusHandoff.current
                    Box(Modifier.padding(start = 250.dp, top = 50.dp)) {
                        SetupButton(
                            text = "Content",
                            onClick = {},
                            focusRequester = content,
                            modifier = Modifier.onPreviewKeyEvent { event ->
                                if (event.key == Key.DirectionLeft) {
                                    handoffNavigationFocus(event, authorizeHandoff)
                                } else false
                            }
                        )
                    }
                }
            }
        }
        requestNodeFocus("Content", content)
        compose.onNodeWithText("Content").performKeyInput { pressKey(Key.DirectionLeft) }
        assertNodeFocused("Home")

        dispatchHeldKey(requireNotNull(rootView), AndroidKeyEvent.KEYCODE_DPAD_RIGHT)

        compose.runOnIdle { assertEquals("held key repeats must not reopen the handoff", 1, handoffs) }
    }

    @Test fun heldDownAtTopNavigationBoundaryEntersContentOnlyOnce() {
        val topNavigationRequesters = NavDestination.entries.associateWith { FocusRequester() }
        var rootView: View? = null
        var entries = 0
        val content = FocusRequester()
        compose.setContent {
            rootView = LocalView.current
            MaterialTheme {
                TopNavigationBar(
                    currentDestination = NavDestination.Home,
                    currentProfile = null,
                    topNavRequesters = topNavigationRequesters,
                    onNavigate = {},
                    onEnterContent = { entries++ }
                ) {
                    val authorizeHandoff = LocalNavigationFocusHandoff.current
                    Box(Modifier.padding(top = 100.dp)) {
                        SetupButton(
                            text = "Content",
                            onClick = {},
                            focusRequester = content,
                            modifier = Modifier.onPreviewKeyEvent { event ->
                                if (event.key == Key.DirectionUp) {
                                    handoffNavigationFocus(event, authorizeHandoff)
                                } else false
                            }
                        )
                    }
                }
            }
        }
        requestNodeFocus("Content", content)
        compose.onNodeWithText("Content").performKeyInput { pressKey(Key.DirectionUp) }
        assertNodeFocused("Home")

        dispatchHeldKey(requireNotNull(rootView), AndroidKeyEvent.KEYCODE_DPAD_DOWN)

        compose.runOnIdle { assertEquals("held key repeats must not re-enter content", 1, entries) }
    }

    @Test fun unsolicitedTopProfileFocusReturnsToContentWithoutOpeningNavigation() {
        val topNavigationRequesters = NavDestination.entries.associateWith { FocusRequester() }
        val content = FocusRequester()
        var entries = 0
        var navigations = 0
        compose.setContent {
            MaterialTheme {
                TopNavigationBar(
                    currentDestination = NavDestination.Home,
                    currentProfile = null,
                    topNavRequesters = topNavigationRequesters,
                    onNavigate = { navigations++ },
                    onEnterContent = {
                        entries++
                        content.requestFocus()
                    }
                ) {
                    Box(Modifier.padding(top = 100.dp)) {
                        SetupButton(text = "Browse content", onClick = {}, focusRequester = content)
                    }
                }
            }
        }
        requestNodeFocus("Browse content", contentRequester)

        compose.runOnIdle { topNavigationRequesters.getValue(NavDestination.Profile).requestFocus() }
        compose.waitForIdle()

        assertNodeFocused("Browse content")
        compose.onNodeWithText("Profile").assertIsNotFocused()
        compose.runOnIdle {
            assertEquals("rejected automatic focus must not navigate", 0, navigations)
            assertEquals("rejected automatic focus must return to content once", 1, entries)
        }
    }

    private fun dispatchHeldKey(view: View, keyCode: Int) {
        val downTime = SystemClock.uptimeMillis()
        compose.runOnUiThread {
            view.dispatchKeyEvent(AndroidKeyEvent(
                downTime, downTime, AndroidKeyEvent.ACTION_DOWN, keyCode, 0
            ))
            view.dispatchKeyEvent(AndroidKeyEvent(
                downTime, downTime + 120, AndroidKeyEvent.ACTION_DOWN, keyCode, 1
            ))
            view.dispatchKeyEvent(AndroidKeyEvent(
                downTime, downTime + 130, AndroidKeyEvent.ACTION_UP, keyCode, 0
            ))
        }
    }

    private fun requestNodeFocus(label: String, requester: FocusRequester) {
        compose.runOnIdle { requester.requestFocus() }
        assertNodeFocused(label)
    }

    private fun assertNodeFocused(label: String) {
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithText(label).assertIsFocused(); true }.getOrDefault(false)
        }
    }
}
