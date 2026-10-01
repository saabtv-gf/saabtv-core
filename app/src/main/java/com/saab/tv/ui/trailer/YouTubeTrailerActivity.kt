package com.saab.tv.ui.trailer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.Button
import com.saab.tv.data.trailer.*
import com.saab.tv.ui.player.PlayerScreen
import com.saab.tv.ui.player.base.PlaybackSettings
import com.saab.tv.ui.theme.SaabTvTheme
import com.saab.tv.ui.theme.ThemeManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Trailer route uses the same Saab TV controls/rendering backend as full playback. */
@AndroidEntryPoint
class YouTubeTrailerActivity : ComponentActivity() {
    @Inject lateinit var extractor: YouTubeExtractor
    @Inject lateinit var dao: com.saab.tv.data.local.AddonDao
    companion object {
        private const val VIDEO = "youtube_video_id"
        private const val TITLE = "trailer_title"
        fun createIntent(context: Context, rawVideoId: String, title: String): Intent? =
            TrailerPolicy.videoId(rawVideoId)?.let {
                Intent(context, YouTubeTrailerActivity::class.java).putExtra(VIDEO, it).putExtra(TITLE, title)
            }
    }
    private var source by mutableStateOf<TrailerPlaybackSource?>(null)
    private var failed by mutableStateOf(false)
    private var theme by mutableStateOf(com.saab.tv.ui.theme.DefaultThemes.VOID)
    private var job: Job? = null
    private var videoId = ""
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        videoId = TrailerPolicy.videoId(intent.getStringExtra(VIDEO).orEmpty()) ?: run { finish(); return }
        setContent {
            SaabTvTheme(theme = theme) {
                val resolved = source
                if (resolved != null) {
                    PlayerScreen(
                        videoUrl = resolved.videoUrl, trailerAudioUrl = resolved.audioUrl,
                        trailerVariants = listOf(TrailerPlaybackVariant(resolved.videoUrl, resolved.audioUrl,
                            resolved.qualityLabel, requestHeaders = resolved.requestHeaders)) + resolved.fallbackVariants,
                        title = intent.getStringExtra(TITLE).orEmpty().ifBlank { "Trailer" },
                        poster = "", movieId = "trailer_$videoId", mediaType = "movie",
                        playbackSettings = PlaybackSettings(seekThumbnailsEnabled = false),
                        onBack = { finish() }
                    )
                } else {
                    BackHandler { finish() }
                    Column(Modifier.fillMaxSize().background(Color.Black), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)) {
                        if (!failed) { CircularProgressIndicator(); Text("Loading Trailer", color = Color.White) }
                        else {
                            Text("Trailer Unavailable", color = Color.White)
                            Button(onClick = { load() }) { Text("Try Again") }
                            Button(onClick = { finish() }) { Text("Back") }
                        }
                    }
                }
            }
        }
        load()
    }
    private fun load() {
        job?.cancel(); source = null; failed = false
        job = lifecycleScope.launch {
            val profile = dao.getActiveProfileId()?.let { dao.getProfileById(it) }
            profile?.themeId?.let {
                theme = dao.getThemeById(it) ?: com.saab.tv.ui.theme.DefaultThemes.getById(it)
            }
            source = extractor.extractPlaybackSource(videoId)
            failed = source == null
        }
    }
    override fun onDestroy() { job?.cancel(); super.onDestroy() }
}
