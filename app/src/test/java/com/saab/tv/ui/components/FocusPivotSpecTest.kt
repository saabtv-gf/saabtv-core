package com.saab.tv.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalFoundationApi::class)
class FocusPivotSpecTest {
    @Test fun defaultShelfAnchorAndCustomOffsetAreSupported() {
        assertEquals(60f, FocusPivotSpec().calculateScrollDistance(160f, 120f, 1000f), 0f)

        val spec = FocusPivotSpec(pivotFraction = .7f, customOffset = 40f, itemPivotFraction = .5f)
        assertEquals(60f, spec.calculateScrollDistance(100f, 120f, 1000f), 0f)
    }

    @Test fun centerAnchorKeepsTheSelectedItemAtViewportCenter() {
        val spec = FocusPivotSpec(pivotFraction = .5f, itemPivotFraction = .5f)

        assertEquals(-200f, spec.calculateScrollDistance(200f, 200f, 1000f), 0f)
        assertEquals(200f, spec.calculateScrollDistance(600f, 200f, 1000f), 0f)
    }

    @Test fun focusedCardMovesToTheFixedShelfPivot() {
        val spec = FocusPivotSpec(customOffset = 60f)

        assertEquals(200f, spec.calculateScrollDistance(offset = 260f, size = 120f, containerSize = 1280f), 0f)
        assertEquals(-100f, spec.calculateScrollDistance(offset = -40f, size = 120f, containerSize = 1280f), 0f)
    }

    @Test fun restorationCanSkipInitialMotionThenPivotNormallyAfterNavigation() {
        var skip = true
        val spec = FocusPivotSpec(customOffset = 60f, skipScrollProvider = { skip })

        assertEquals(0f, spec.calculateScrollDistance(offset = 200f, size = 120f, containerSize = 1280f), 0f)
        skip = false
        assertEquals(140f, spec.calculateScrollDistance(offset = 200f, size = 120f, containerSize = 1280f), 0f)
    }

    @Test fun sharedScrollSpringIsAvailableToCompose() {
        val getter = FocusPivotSpec::class.java.getDeclaredMethod("getScrollAnimationSpec")

        val pivotSpec = getter.invoke(FocusPivotSpec()) as SpringSpec<Float>
        val sharedSpec = SaabMotion.focusRetargetSpec<Float>() as SpringSpec<Float>

        assertEquals(sharedSpec.stiffness, pivotSpec.stiffness, 0f)
        assertEquals(sharedSpec.dampingRatio, pivotSpec.dampingRatio, 0f)
        assertTrue(FocusPivotSpec.DEFAULT_STIFFNESS > 0f)
    }

    @Test fun sharedScrollSpringHonorsReducedMotion() {
        assertTrue(SaabMotion.focusRetargetSpec<Float>(reducedMotion = true) is SnapSpec<*>)
    }

    @Test fun systemAnimationDisableAlsoSelectsReducedMotion() {
        assertTrue(SaabMotion.shouldReduceMotion(userPreference = false, systemAnimationsEnabled = false))
        assertTrue(SaabMotion.shouldReduceMotion(userPreference = true, systemAnimationsEnabled = true))
        assertEquals(false, SaabMotion.shouldReduceMotion(userPreference = false, systemAnimationsEnabled = true))
    }

    @Test fun programmaticScrollingUsesTheSameUserAndSystemMotionPolicy() {
        assertEquals(true, SaabMotion.shouldAnimate(userPreference = false, systemAnimationsEnabled = true))
        assertEquals(false, SaabMotion.shouldAnimate(userPreference = true, systemAnimationsEnabled = true))
        assertEquals(false, SaabMotion.shouldAnimate(userPreference = false, systemAnimationsEnabled = false))
    }

    @Test fun sharedMotionSpecsKeepFocusAndNavigationTimingsConsistent() {
        assertEquals(120, SaabMotion.FOCUS_FEEDBACK_MS)
        val retarget = SaabMotion.focusRetargetSpec<Float>() as SpringSpec<Float>
        assertEquals(FocusPivotSpec.DEFAULT_STIFFNESS, retarget.stiffness, 0f)
        assertEquals(Spring.DampingRatioNoBouncy, retarget.dampingRatio, 0f)

        assertEquals(SaabMotion.FOCUS_FEEDBACK_MS,
            (SaabMotion.focusFeedbackSpec<Float>() as TweenSpec<Float>).durationMillis)
        assertEquals(SaabMotion.FOCUS_LABEL_MS,
            (SaabMotion.focusLabelSpec<Float>() as TweenSpec<Float>).durationMillis)
        assertEquals(SaabMotion.NAVIGATION_REVEAL_MS,
            (SaabMotion.navigationRevealSpec<Float>() as TweenSpec<Float>).durationMillis)
        assertEquals(240, SaabMotion.HERO_BACKDROP_IN_MS)
        assertEquals(200, SaabMotion.HERO_BACKDROP_OUT_MS)
        assertEquals(160, SaabMotion.HERO_INFO_IN_MS)
        assertEquals(120, SaabMotion.HERO_INFO_OUT_MS)
    }

    @Test fun nearAlignedCardDoesNotMicroScroll() {
        val spec = FocusPivotSpec(customOffset = 60f)

        assertEquals(0f, spec.calculateScrollDistance(offset = 60.5f, size = 120f, containerSize = 1280f), 0f)
    }

    @Test fun focusFrameMatchesConstrainedPosterAndLandscapeCards() {
        assertEquals(FocusFrameSize(120.dp, 180.dp), focusFrameSize(120.dp, isLandscape = false))
        assertEquals(FocusFrameSize(190.dp, 106.875.dp), focusFrameSize(190.dp, isLandscape = true))
    }
}
