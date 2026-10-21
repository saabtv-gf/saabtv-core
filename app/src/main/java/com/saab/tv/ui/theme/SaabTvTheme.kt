package com.saab.tv.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.saab.tv.data.model.ThemeEntity
import com.saab.tv.ui.components.SaabMotion
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

@Composable
fun SaabTvTheme(
    theme: ThemeEntity = DefaultThemes.VOID,
    content: @Composable () -> Unit
) {
    // Animate all colors for smooth theme transitions
    val primaryColor by animateColorAsState(
        targetValue = Color(theme.primaryColor.toInt()),
        animationSpec = SaabMotion.navigationRevealSpec()
    )
    val backgroundColor by animateColorAsState(
        targetValue = Color(theme.backgroundColor.toInt()),
        animationSpec = SaabMotion.navigationRevealSpec()
    )
    val surfaceColor by animateColorAsState(
        targetValue = Color(theme.surfaceColor.toInt()),
        animationSpec = SaabMotion.navigationRevealSpec()
    )
    val textColor by animateColorAsState(
        targetValue = Color(theme.textColor.toInt()),
        animationSpec = SaabMotion.navigationRevealSpec()
    )
    val mutedTextColor by animateColorAsState(
        targetValue = Color(theme.textMutedColor.toInt()),
        animationSpec = SaabMotion.navigationRevealSpec()
    )
    val surfaceVariant = themedSurfaceVariant(surfaceColor)
    val errorColor by animateColorAsState(
        targetValue = Color(theme.errorColor.toInt()),
        animationSpec = SaabMotion.navigationRevealSpec()
    )
    val titleTextColor = readableTextColor(textColor, surfaceColor)
    val secondaryTextColor = readableTextColor(mutedTextColor, surfaceVariant)
    val mobileTypography = SaabTvTypography.copy(
        titleLarge = SaabTvTypography.titleLarge.copy(color = titleTextColor),
        titleMedium = SaabTvTypography.titleMedium.copy(color = titleTextColor),
        bodyLarge = SaabTvTypography.bodyLarge.copy(color = secondaryTextColor),
        labelSmall = SaabTvTypography.labelSmall.copy(color = secondaryTextColor)
    )
    val tvTypography = SaabTvTvTypography.copy(
        titleLarge = SaabTvTvTypography.titleLarge.copy(color = titleTextColor),
        titleMedium = SaabTvTvTypography.titleMedium.copy(color = titleTextColor),
        bodyLarge = SaabTvTvTypography.bodyLarge.copy(color = secondaryTextColor),
        labelSmall = SaabTvTvTypography.labelSmall.copy(color = secondaryTextColor)
    )

    // A. Configure Mobile Colors
    val mobileScheme = mobileDarkColorScheme(
        primary = primaryColor,
        secondary = primaryColor,
        background = backgroundColor,
        surface = surfaceColor,
        surfaceVariant = surfaceVariant,
        onPrimary = accessibleContentColor(primaryColor),
        onBackground = readableTextColor(textColor, backgroundColor),
        onSurface = readableTextColor(textColor, surfaceColor),
        onSurfaceVariant = readableTextColor(mutedTextColor, surfaceVariant),
        error = errorColor,
        onError = accessibleContentColor(errorColor)
    )

    // B. Configure TV Colors (Crucial for SaabTvCard Glow)
    val tvScheme = tvDarkColorScheme(
        primary = primaryColor,
        secondary = primaryColor,
        background = backgroundColor,
        surface = surfaceColor,
        surfaceVariant = surfaceVariant,
        onPrimary = accessibleContentColor(primaryColor),
        onSurface = readableTextColor(textColor, surfaceColor),
        onSurfaceVariant = readableTextColor(mutedTextColor, surfaceVariant),
        error = errorColor,
        onError = accessibleContentColor(errorColor)
    )

    MobileMaterialTheme(
        colorScheme = mobileScheme,
        typography = mobileTypography
    ) {
        TvMaterialTheme(
            colorScheme = tvScheme,
            typography = tvTypography,
            content = content
        )
    }
}
