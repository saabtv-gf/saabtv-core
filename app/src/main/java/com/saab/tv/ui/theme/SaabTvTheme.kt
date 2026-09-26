package com.saab.tv.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.saab.tv.data.model.ThemeEntity
// 1. Alias the Mobile Theme (For Settings, Dialogs)
import androidx.compose.material3.MaterialTheme as MobileMaterialTheme
import androidx.compose.material3.darkColorScheme as mobileDarkColorScheme
// 2. Alias the TV Theme (For Cards, Home Screen)
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.darkColorScheme as tvDarkColorScheme

// CompositionLocal for poster corner style (true = round, false = sharp)
val LocalRoundCorners = staticCompositionLocalOf { true }

// CompositionLocal for hub corner style (true = round, false = sharp)
val LocalHubRoundCorners = staticCompositionLocalOf { true }

// Animation duration for theme transitions
private const val THEME_ANIMATION_DURATION = 400

@Composable
fun SaabTvTheme(
    theme: ThemeEntity = DefaultThemes.VOID,
    content: @Composable () -> Unit
) {
    // Animate all colors for smooth theme transitions
    val primaryColor by animateColorAsState(
        targetValue = Color(theme.primaryColor.toInt()),
        animationSpec = tween(THEME_ANIMATION_DURATION)
    )
    val backgroundColor by animateColorAsState(
        targetValue = Color(theme.backgroundColor.toInt()),
        animationSpec = tween(THEME_ANIMATION_DURATION)
    )
    val surfaceColor by animateColorAsState(
        targetValue = Color(theme.surfaceColor.toInt()),
        animationSpec = tween(THEME_ANIMATION_DURATION)
    )
    val textColor by animateColorAsState(
        targetValue = Color(theme.textColor.toInt()),
        animationSpec = tween(THEME_ANIMATION_DURATION)
    )
    val errorColor by animateColorAsState(
        targetValue = Color(theme.errorColor.toInt()),
        animationSpec = tween(THEME_ANIMATION_DURATION)
    )

    // A. Configure Mobile Colors
    val mobileScheme = mobileDarkColorScheme(
        primary = primaryColor,
        secondary = primaryColor,
        background = backgroundColor,
        surface = surfaceColor,
        onPrimary = Color.Black,
        onBackground = textColor,
        onSurface = textColor,
        error = errorColor
    )

    // B. Configure TV Colors (Crucial for SaabTvCard Glow)
    val tvScheme = tvDarkColorScheme(
        primary = primaryColor,
        secondary = primaryColor,
        background = backgroundColor,
        surface = surfaceColor,
        onPrimary = Color.Black,
        onSurface = textColor,
        error = errorColor
    )

    MobileMaterialTheme(
        colorScheme = mobileScheme,
        typography = SaabTvTypography
    ) {
        TvMaterialTheme(
            colorScheme = tvScheme,
            typography = SaabTvTvTypography,
            content = content
        )
    }
}
