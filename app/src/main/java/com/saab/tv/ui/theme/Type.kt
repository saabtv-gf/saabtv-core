package com.saab.tv.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Typography as TvTypography
import com.saab.tv.R

val LatoFontFamily = FontFamily(
    Font(R.font.lato_regular, FontWeight.Normal),
    Font(R.font.lato_medium, FontWeight.Medium),
    Font(R.font.lato_semibold, FontWeight.SemiBold),
    Font(R.font.lato_bold, FontWeight.Bold)
)

private val defaultTypography = Typography()

// Apply Lato to every Material 3 text role so screens inherit one font system.
val SaabTvTypography = defaultTypography.copy(
    displayLarge = defaultTypography.displayLarge.copy(fontFamily = LatoFontFamily),
    displayMedium = defaultTypography.displayMedium.copy(fontFamily = LatoFontFamily),
    displaySmall = defaultTypography.displaySmall.copy(fontFamily = LatoFontFamily),
    headlineLarge = defaultTypography.headlineLarge.copy(fontFamily = LatoFontFamily),
    headlineMedium = defaultTypography.headlineMedium.copy(fontFamily = LatoFontFamily),
    headlineSmall = defaultTypography.headlineSmall.copy(fontFamily = LatoFontFamily),
    titleLarge = defaultTypography.titleLarge.copy(
        fontFamily = LatoFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        color = TextPrimary
    ),
    titleMedium = defaultTypography.titleMedium.copy(
        fontFamily = LatoFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        color = TextPrimary
    ),
    titleSmall = defaultTypography.titleSmall.copy(fontFamily = LatoFontFamily),
    bodyLarge = defaultTypography.bodyLarge.copy(
        fontFamily = LatoFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        lineHeight = 28.sp,
        color = TextSecondary
    ),
    bodyMedium = defaultTypography.bodyMedium.copy(fontFamily = LatoFontFamily),
    bodySmall = defaultTypography.bodySmall.copy(fontFamily = LatoFontFamily),
    labelLarge = defaultTypography.labelLarge.copy(fontFamily = LatoFontFamily),
    labelMedium = defaultTypography.labelMedium.copy(fontFamily = LatoFontFamily),
    labelSmall = defaultTypography.labelSmall.copy(
        fontFamily = LatoFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = TextSecondary
    )
)

// TV Material uses a separate theme object, so mirror every mobile text role.
val SaabTvTvTypography = TvTypography(
    displayLarge = SaabTvTypography.displayLarge,
    displayMedium = SaabTvTypography.displayMedium,
    displaySmall = SaabTvTypography.displaySmall,
    headlineLarge = SaabTvTypography.headlineLarge,
    headlineMedium = SaabTvTypography.headlineMedium,
    headlineSmall = SaabTvTypography.headlineSmall,
    titleLarge = SaabTvTypography.titleLarge,
    titleMedium = SaabTvTypography.titleMedium,
    titleSmall = SaabTvTypography.titleSmall,
    bodyLarge = SaabTvTypography.bodyLarge,
    bodyMedium = SaabTvTypography.bodyMedium,
    bodySmall = SaabTvTypography.bodySmall,
    labelLarge = SaabTvTypography.labelLarge,
    labelMedium = SaabTvTypography.labelMedium,
    labelSmall = SaabTvTypography.labelSmall
)
