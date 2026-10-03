package com.saab.tv.ui.trailer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.hilt.navigation.compose.hiltViewModel
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.data.profile.rememberTrailerPreviewSettings
import com.saab.tv.ui.home.HomeViewModel

private val LocalTitleFocus = staticCompositionLocalOf<(MetaItem?, FocusRequester) -> Unit> { { _, _ -> } }
val LocalTrailerEpisodesAction = staticCompositionLocalOf<(() -> Unit)?> { null }
val LocalTrailerStartWatching = staticCompositionLocalOf<((MetaItem) -> Unit)?> { null }

/** Shared fullscreen preview host for recommendation, cast and studio title shelves. */
@Composable
fun TitleTrailerHost(onOpen: (MetaItem) -> Unit, defaultItem: MetaItem? = null,
    defaultEnabled: Boolean = true, onEpisodes: (() -> Unit)? = null,
    model: HomeViewModel? = null, content: @Composable () -> Unit) {
    val resolvedModel = model ?: hiltViewModel()
    var profileId by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(resolvedModel) { profileId = resolvedModel.activeProfileId() }
    var item by remember { mutableStateOf<MetaItem?>(null) }
    var requester by remember { mutableStateOf<FocusRequester?>(null) }
    val contentRequester = remember { FocusRequester() }
    var active by remember { mutableStateOf(false) }
    var activityVersion by remember(defaultItem?.id) { mutableIntStateOf(0) }
    val observeDefaultActivity by rememberUpdatedState(defaultItem != null && !active)
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().focusRequester(contentRequester).onPreviewKeyEvent {
            if (observeDefaultActivity && it.type == KeyEventType.KeyDown) activityVersion++
            false // Observe activity without consuming the page's input.
        }.pointerInput(defaultItem?.id) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (observeDefaultActivity && event.changes.any { it.pressed }) activityVersion++
                }
            }
        }) {
        CompositionLocalProvider(LocalTitleFocus provides { next, focus ->
            if (next != null) { item = next; requester = focus }
            else if (!active && requester === focus) item = null
        }) { content() }
        }
        val settings = rememberTrailerPreviewSettings(profileId ?: 0)
        BackdropTrailerPreview(focusedItem = item ?: defaultItem, catalog = emptyList(), profileId = profileId ?: 0,
            settings = if (defaultItem != null) settings.copy(presentation = "fullscreen") else settings,
            enabled = !LocalManualTrailerActive.current && profileId != null && defaultEnabled,
            resolveTrailer = resolvedModel::trailerFor, onActiveChanged = { active = it }, onOpen = onOpen,
            onDismiss = { runCatching { requester?.requestFocus() ?: contentRequester.requestFocus() } },
            activityVersion = activityVersion,
            homeViewModel = resolvedModel,
            onEpisodes = if ((item ?: defaultItem)?.type == "series" &&
                (item ?: defaultItem)?.id == defaultItem?.id) onEpisodes else null)
    }
}

@Composable
fun Modifier.titleTrailerFocus(item: MetaItem): Modifier {
    val requester = remember { FocusRequester() }
    val callback = LocalTitleFocus.current
    return trailerAnchor(item).focusRequester(requester).onFocusChanged {
        callback(if (it.isFocused) item else null, requester)
    }
}
