package com.saab.tv.ui.trailer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.data.profile.TrailerPreviewSettings
import com.saab.tv.ui.home.HomeViewModel

val LocalManualTrailerActive = staticCompositionLocalOf { false }

val LocalManualTrailerLauncher =
    staticCompositionLocalOf<((MetaItem, Pair<String, String>?, (() -> Unit)?) -> Unit)?> { null }

private class ManualTrailerRequest(val item: MetaItem, val trailer: Pair<String, String>?, val onEpisodes: (() -> Unit)?)

/** Manual requests always use the fullscreen preview and its shared details actions. */
@Composable
fun ManualTrailerHost(profileId: Int, content: @Composable () -> Unit) {
    val model: HomeViewModel = hiltViewModel()
    var request by remember { mutableStateOf<ManualTrailerRequest?>(null) }
    var playing by remember(request) { mutableStateOf(false) }
    LaunchedEffect(profileId) { request = null }
    val context = androidx.compose.ui.platform.LocalContext.current
    val startWatching = LocalTrailerStartWatching.current
    androidx.activity.compose.BackHandler(enabled = request != null) { request = null }
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalManualTrailerActive provides (request != null), LocalManualTrailerLauncher provides { item, trailer, episodes ->
            request = ManualTrailerRequest(item, trailer, episodes)
        }) { content() }
        request?.let { current ->
            if (!playing) {
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black),
                    contentAlignment = androidx.compose.ui.Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator()
                }
            }
            key(current) {
                CompositionLocalProvider(LocalTrailerEpisodesAction provides current.onEpisodes?.let { action ->
                    { request = null; action() }
                }) {
                BackdropTrailerPreview(
                    focusedItem = current.item, catalog = emptyList(), profileId = profileId,
                    settings = TrailerPreviewSettings(enabled = true, presentation = "fullscreen",
                        delaySeconds = 0, muted = false),
                    enabled = true,
                    resolveTrailer = {
                        val trailer = current.trailer ?: model.trailerFor(it)
                        if (trailer == null) {
                            android.widget.Toast.makeText(context, "No Trailer Available",
                                android.widget.Toast.LENGTH_SHORT).show()
                            request = null
                        }
                        trailer
                    },
                    onActiveChanged = { playing = it },
                    onUnavailable = {
                        android.widget.Toast.makeText(context, "Trailer Unavailable",
                            android.widget.Toast.LENGTH_SHORT).show()
                        request = null
                    },
                    onOpen = { request = null; startWatching?.invoke(it) },
                    onDismiss = { request = null }
                )
                }
            }
        }
    }
}
