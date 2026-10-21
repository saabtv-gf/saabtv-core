package com.saab.tv.ui.trailer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.R
import com.saab.tv.data.profile.TrailerPreviewSettings
import com.saab.tv.ui.home.HomeViewModel

val LocalManualTrailerActive = staticCompositionLocalOf { false }

val LocalManualTrailerLauncher =
    staticCompositionLocalOf<((MetaItem, Pair<String, String>?, (() -> Unit)?, ((MetaItem) -> Unit)?) -> Unit)?> { null }

private class ManualTrailerRequest(
    val item: MetaItem,
    val trailer: Pair<String, String>?,
    val onEpisodes: (() -> Unit)?,
    val onStartWatching: ((MetaItem) -> Unit)?
)

/** Manual requests always use the fullscreen preview and its shared details actions. */
@Composable
fun ManualTrailerHost(profileId: Int, content: @Composable () -> Unit) {
    val model: HomeViewModel = hiltViewModel()
    var request by remember { mutableStateOf<ManualTrailerRequest?>(null) }
    var playing by remember(request) { mutableStateOf(false) }
    LaunchedEffect(profileId) { request = null }
    val context = androidx.compose.ui.platform.LocalContext.current
    val noTrailerMessage = androidx.compose.ui.res.stringResource(R.string.quick_action_no_trailer)
    val trailerUnavailableMessage = androidx.compose.ui.res.stringResource(R.string.trailer_unavailable)
    val startWatching = LocalTrailerStartWatching.current
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalManualTrailerActive provides (request != null), LocalManualTrailerLauncher provides { item, trailer, episodes, handoff ->
            request = ManualTrailerRequest(item, trailer, episodes, handoff)
        }) { content() }
        // Register after underlying screens so Back during resolution closes this overlay first.
        com.saab.tv.ui.navigation.AppBackHandler(
            enabled = request != null,
            priority = com.saab.tv.ui.navigation.AppBackPriority.ManualPreview
        ) { request = null }
        request?.let { current ->
            val handoff = current.onStartWatching ?: startWatching
            if (!playing) {
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black),
                    contentAlignment = androidx.compose.ui.Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator()
                }
            }
            key(current) {
                CompositionLocalProvider(LocalTrailerStartWatching provides { item ->
                    request = null
                    handoff?.invoke(item)
                }, LocalTrailerEpisodesAction provides current.onEpisodes?.let { action ->
                    { request = null; action() }
                }) {
                BackdropTrailerPreview(
                    focusedItem = current.item, profileId = profileId,
                    settings = TrailerPreviewSettings(enabled = true, presentation = "fullscreen",
                        delaySeconds = 0, muted = false),
                    enabled = true,
                    resolveTrailer = {
                        val trailer = current.trailer ?: model.trailerFor(it)
                        if (trailer == null) {
                            android.widget.Toast.makeText(context, noTrailerMessage,
                                android.widget.Toast.LENGTH_SHORT).show()
                            request = null
                        }
                        trailer
                    },
                    onActiveChanged = { playing = it },
                    onUnavailable = {
                        android.widget.Toast.makeText(context, trailerUnavailableMessage,
                            android.widget.Toast.LENGTH_SHORT).show()
                        request = null
                    },
                    onOpen = { request = null; handoff?.invoke(it) },
                    onDismiss = { request = null },
                    homeViewModel = model
                )
                }
            }
        }
    }
}
