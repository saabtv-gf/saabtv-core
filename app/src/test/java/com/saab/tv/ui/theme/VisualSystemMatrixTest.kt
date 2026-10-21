package com.saab.tv.ui.theme

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme as MobileTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme as TvTheme
import com.saab.tv.data.model.ThemeEntity
import com.saab.tv.ui.components.SetupButton
import com.saab.tv.ui.components.SetupHeader
import com.saab.tv.ui.components.setupButtonTextColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land")
class VisualSystemMatrixTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun everyBuiltInThemeHasReadableForegroundsForSemanticSurfacesAndActions() {
        DefaultThemes.ALL.forEach { theme ->
            val background = Color(theme.backgroundColor.toInt())
            val surface = Color(theme.surfaceColor.toInt())
            val variant = themedSurfaceVariant(surface)
            val primary = Color(theme.primaryColor.toInt())
            val error = Color(theme.errorColor.toInt())

            assertContrast(readableTextColor(Color(theme.textColor.toInt()), background), background, "${theme.name} background")
            assertContrast(readableTextColor(Color(theme.textColor.toInt()), surface), surface, "${theme.name} surface")
            assertContrast(
                readableTextColor(Color(theme.textMutedColor.toInt()), variant),
                variant,
                "${theme.name} secondary surface"
            )
            assertContrast(accessibleContentColor(primary), primary, "${theme.name} primary action")
            assertContrast(accessibleContentColor(error), error, "${theme.name} destructive action")
        }
    }

    @Test
    fun customLowContrastTextFallsBackWithoutChangingReadableCustomColors() {
        val darkSurface = Color(0xFF080A0D)
        val brightSurface = Color(0xFFF7F7F7)

        assertEquals(Color.White, readableTextColor(Color.Black, darkSurface))
        assertEquals(Color.Black, readableTextColor(Color.White, brightSurface))
        assertEquals(Color(0xFFECEFF1), readableTextColor(Color(0xFFECEFF1), darkSurface))
        assertContrast(readableTextColor(Color(0xFF777777), Color(0x80000000)), Color(0x80000000), "translucent surface")
    }

    @Test
    fun mobileAndTvSchemesUseTheSameSafeCustomThemeRoles() {
        val lowContrastTheme: ThemeEntity = DefaultThemes.VOID.copy(
            primaryColor = 0xFFFFEA00,
            backgroundColor = 0xFF050607,
            surfaceColor = 0xFF080A0D,
            textColor = 0xFF000000,
            textMutedColor = 0xFF111111,
            errorColor = 0xFFFF1744
        )
        var mobile: androidx.compose.material3.ColorScheme? = null
        var tv: androidx.tv.material3.ColorScheme? = null
        var mobileTypography: androidx.compose.material3.Typography? = null
        var tvTypography: androidx.tv.material3.Typography? = null

        compose.setContent {
            SaabTvTheme(lowContrastTheme) {
                mobile = MobileTheme.colorScheme
                tv = TvTheme.colorScheme
                mobileTypography = MobileTheme.typography
                tvTypography = TvTheme.typography
            }
        }

        compose.runOnIdle {
            val mobileScheme = requireNotNull(mobile)
            val tvScheme = requireNotNull(tv)
            val materialTypography = requireNotNull(mobileTypography)
            val tvMaterialTypography = requireNotNull(tvTypography)
            assertEquals(mobileScheme.surfaceVariant, tvScheme.surfaceVariant)
            assertEquals(mobileScheme.onSurfaceVariant, tvScheme.onSurfaceVariant)
            assertContrast(mobileScheme.onBackground, mobileScheme.background, "mobile background")
            assertContrast(mobileScheme.onSurface, mobileScheme.surface, "mobile surface")
            assertContrast(mobileScheme.onSurfaceVariant, mobileScheme.surfaceVariant, "mobile variant")
            assertContrast(mobileScheme.onPrimary, mobileScheme.primary, "mobile primary")
            assertContrast(mobileScheme.onError, mobileScheme.error, "mobile error")
            assertContrast(tvScheme.onSurface, tvScheme.surface, "TV surface")
            assertContrast(tvScheme.onSurfaceVariant, tvScheme.surfaceVariant, "TV variant")
            assertContrast(materialTypography.titleLarge.color, mobileScheme.surface, "mobile title typography")
            assertContrast(materialTypography.bodyLarge.color, mobileScheme.surfaceVariant, "mobile secondary typography")
            assertContrast(tvMaterialTypography.titleLarge.color, tvScheme.surface, "TV title typography")
            assertContrast(tvMaterialTypography.bodyLarge.color, tvScheme.surfaceVariant, "TV secondary typography")
        }
    }

    @Test
    fun typographyKeepsOneFontFamilyAcrossMobileAndTvRoles() {
        val mobileRoles = listOf(
            SaabTvTypography.displayLarge,
            SaabTvTypography.headlineMedium,
            SaabTvTypography.titleLarge,
            SaabTvTypography.bodyLarge,
            SaabTvTypography.labelSmall
        )
        val tvRoles = listOf(
            SaabTvTvTypography.displayLarge,
            SaabTvTvTypography.headlineMedium,
            SaabTvTvTypography.titleLarge,
            SaabTvTvTypography.bodyLarge,
            SaabTvTvTypography.labelSmall
        )

        assertTrue(mobileRoles.all { it.fontFamily == LatoFontFamily })
        assertTrue(tvRoles.all { it.fontFamily == LatoFontFamily })
    }

    @Test
    fun buttonNormalFocusedDisabledAndDestructiveStatesKeepReadableTextAndSharedGeometry() {
        assertEquals(2.dp, SaabVisualTokens.focusOutlineWidth)
        assertEquals(12.dp, SaabVisualTokens.focusCornerRadius)
        assertEquals(48.dp, SaabVisualTokens.controlMinHeight)
        assertEquals(40.dp, SaabVisualTokens.compactControlMinHeight)

        DefaultThemes.ALL.forEach { theme ->
            val primary = Color(theme.primaryColor.toInt())
            val error = Color(theme.errorColor.toInt())
            val surfaceVariant = themedSurfaceVariant(Color(theme.surfaceColor.toInt()))
            val onPrimary = accessibleContentColor(primary)
            val onError = accessibleContentColor(error)
            val onSurfaceVariant = readableTextColor(Color(theme.textMutedColor.toInt()), surfaceVariant)

            assertContrast(
                setupButtonTextColor(true, false, false, onPrimary, onError, onSurfaceVariant),
                surfaceVariant,
                "${theme.name} normal button"
            )
            assertContrast(
                setupButtonTextColor(true, true, false, onPrimary, onError, onSurfaceVariant),
                primary,
                "${theme.name} focused button"
            )
            assertContrast(
                setupButtonTextColor(true, true, true, onPrimary, onError, onSurfaceVariant),
                error,
                "${theme.name} destructive button"
            )
            assertContrast(
                setupButtonTextColor(false, false, false, onPrimary, onError, onSurfaceVariant),
                Color(0xFF344B65),
                "${theme.name} disabled button"
            )
        }
    }

    @Test
    fun longControlCopyRemainsReachableAndGrowsWithAccessibleFontScale() {
        val longCopy = "Continue watching this episode from where you stopped, with subtitles and audio preferences preserved across devices"
        val longDescription = "Choose the language you prefer for audio and subtitles. Earlier choices take priority when matching a stream. "
            .repeat(3)
        val scale = mutableFloatStateOf(1f)

        compose.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(baseDensity.density, scale.floatValue)) {
                SaabTvTheme {
                    Column(
                        Modifier
                            .width(420.dp)
                            .height(180.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        SetupHeader("Playback language and subtitle preferences", longDescription, "")
                        SetupButton(longCopy, onClick = {}, modifier = Modifier.width(260.dp))
                    }
                }
            }
        }

        val heading = compose.onNodeWithText("Playback language and subtitle preferences")
        heading.assertExists()
        val defaultHeight = heading.fetchSemanticsNode().boundsInRoot.height
        compose.runOnIdle { scale.floatValue = 1.35f }
        heading.performScrollTo().assertExists()
        val accessibleHeight = heading.fetchSemanticsNode().boundsInRoot.height
        assertTrue(
            "Large text should scale instead of clipping (default=$defaultHeight, accessible=$accessibleHeight)",
            accessibleHeight > defaultHeight
        )
        compose.onNodeWithText(longCopy).performScrollTo().assertExists()
    }

    private fun assertContrast(foreground: Color, background: Color, label: String) {
        val ratio = contrastRatio(foreground, background)
        assertTrue("$label contrast was $ratio", ratio >= 4.5f)
    }
}
