package com.saab.tv.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.contentDescription as contentDescriptionSemantics
import androidx.compose.ui.semantics.semantics
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter

/** Keep an image during same-title enrichment, never while a reused card represents another title. */
internal fun artworkPainterForContent(
    current: Painter?,
    previous: Painter?,
    currentContentKey: Any?,
    previousContentKey: Any?
): Painter? = when {
    current != null -> current
    currentContentKey != null && currentContentKey == previousContentKey -> previous
    else -> null
}

/** Use artwork URL only when a card has no stable content identity; title text can collide. */
internal fun artworkContentIdentityKey(
    stableContentKey: Any?,
    artworkUrl: String?,
    title: String
): Any = stableContentKey ?: artworkUrl?.takeIf(String::isNotBlank) ?: title

internal fun shouldShowArtworkFallback(
    hasDisplayedArtwork: Boolean,
    hasArtworkModel: Boolean = false,
    requestFailed: Boolean = false
): Boolean = !hasDisplayedArtwork && (!hasArtworkModel || requestFailed)

@Composable
internal fun RetainedArtworkImage(
    model: Any?,
    contentDescription: String,
    modifier: Modifier,
    contentScale: ContentScale,
    contentIdentityKey: Any? = model,
    alignment: Alignment = Alignment.Center,
    onLoadError: (() -> Unit)? = null,
    unavailable: @Composable () -> Unit
) {
    val contentKey = contentIdentityKey ?: model
    var state by remember(model) { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
    val currentOnLoadError by rememberUpdatedState(onLoadError)
    val painter = rememberAsyncImagePainter(
        model = model,
        onLoading = { state = it },
        onSuccess = { state = it },
        onError = {
            state = it
            currentOnLoadError?.invoke()
        }
    )
    var lastSuccessfulArtwork by remember { mutableStateOf<Pair<Any?, Painter>?>(null) }
    LaunchedEffect(contentKey, model, state) {
        val successfulPainter = (state as? AsyncImagePainter.State.Success)?.painter
        val retained = artworkPainterForContent(
            current = successfulPainter,
            previous = lastSuccessfulArtwork?.second,
            currentContentKey = contentKey,
            previousContentKey = lastSuccessfulArtwork?.first
        )
        lastSuccessfulArtwork = retained?.let { contentKey to it }
    }

    val currentPainter = (state as? AsyncImagePainter.State.Success)?.painter
    val displayedPainter = artworkPainterForContent(
        current = currentPainter,
        previous = lastSuccessfulArtwork?.second,
        currentContentKey = contentKey,
        previousContentKey = lastSuccessfulArtwork?.first
    )
    RetainedArtworkFrame(
        painter = displayedPainter,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        alignment = alignment,
        // Keep the card quiet while valid art is loading; initials/text are for absent or failed art.
        showFallback = shouldShowArtworkFallback(
            hasDisplayedArtwork = displayedPainter != null,
            hasArtworkModel = model != null,
            requestFailed = state is AsyncImagePainter.State.Error
        ),
        unavailable = unavailable
    )
}

@Composable
internal fun RetainedArtworkFrame(
    painter: Painter?,
    contentDescription: String,
    modifier: Modifier,
    contentScale: ContentScale,
    alignment: Alignment = Alignment.Center,
    showFallback: Boolean,
    unavailable: @Composable () -> Unit
) {
    val semanticModifier = modifier.semantics { contentDescriptionSemantics = contentDescription }
    if (painter != null) {
        Image(
            painter = painter,
            contentDescription = contentDescription,
            contentScale = contentScale,
            alignment = alignment,
            modifier = semanticModifier
        )
    } else {
        Box(semanticModifier) {
            if (showFallback) unavailable()
        }
    }
}
