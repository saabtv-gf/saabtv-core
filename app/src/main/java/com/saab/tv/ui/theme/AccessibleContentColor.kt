package com.saab.tv.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp

/** Returns whichever neutral foreground has the strongest contrast on a solid UI fill. */
internal fun accessibleContentColor(background: Color): Color {
    val opaqueBackground = background.compositeOver(Color.Black)
    val luminance = opaqueBackground.luminance()
    val blackContrast = (luminance + 0.05f) / 0.05f
    val whiteContrast = 1.05f / (luminance + 0.05f)
    return if (blackContrast >= whiteContrast) Color.Black else Color.White
}

/** Keeps a requested theme text color when it is readable, otherwise picks a safe neutral. */
internal fun readableTextColor(
    preferred: Color,
    background: Color,
    minimumContrast: Float = 4.5f
): Color {
    val opaqueBackground = background.compositeOver(Color.Black)
    val opaqueForeground = preferred.compositeOver(opaqueBackground)
    return if (contrastRatio(opaqueForeground, opaqueBackground) >= minimumContrast) {
        opaqueForeground
    } else {
        accessibleContentColor(opaqueBackground)
    }
}

/** A subtle lift from the theme surface, rather than Material's unrelated default variant. */
internal fun themedSurfaceVariant(surface: Color): Color =
    lerp(surface.compositeOver(Color.Black), Color.White, 0.08f)

internal fun contrastRatio(foreground: Color, background: Color): Float {
    val foregroundLuminance = foreground.compositeOver(background).luminance()
    val backgroundLuminance = background.compositeOver(Color.Black).luminance()
    val lighter = maxOf(foregroundLuminance, backgroundLuminance)
    val darker = minOf(foregroundLuminance, backgroundLuminance)
    return (lighter + 0.05f) / (darker + 0.05f)
}
