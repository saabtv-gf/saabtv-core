package com.saab.tv.ui.components

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.animation.core.TweenSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land")
class DetailsLoadingSweepMotionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun reduceMotionKeepsDetailsLoadingSweepStationary() {
        withReducedMotion(true) {
            compose.mainClock.autoAdvance = false
            compose.setContent { DetailsLoadingSweep() }
            val sweep = compose.onNodeWithTag("details-loading-sweep")
            sweep.assertExists()
            val initialLeft = sweep.fetchSemanticsNode().boundsInRoot.left

            compose.mainClock.advanceTimeBy(3_000)

            assertEquals(initialLeft, sweep.fetchSemanticsNode().boundsInRoot.left, 0.5f)
        }
    }

    @Test fun normalMotionContinuesTheDetailsLoadingSweep() {
        withSystemAnimationsEnabled(true) {
            compose.mainClock.autoAdvance = false
            compose.setContent { DetailsLoadingSweep() }
            compose.mainClock.advanceTimeByFrame()
            val sweep = compose.onNodeWithTag("details-loading-sweep")
            val initialLeft = sweep.fetchSemanticsNode().boundsInRoot.left

            // At 1,200 ms the sweep is at x=0, which clips to the same visible
            // bounds as its initial off-screen position. Sample after it enters.
            compose.mainClock.advanceTimeBy(1_800)

            assertNotEquals(initialLeft, sweep.fetchSemanticsNode().boundsInRoot.left)
        }
    }

    @Test fun reducedMotionCanBeToggledWhileTheScreenIsVisible() {
        withSystemAnimationsEnabled(true) {
            val context = RuntimeEnvironment.getApplication()
            SaabMotion.setReducedMotion(context, false)
            compose.mainClock.autoAdvance = false
            compose.setContent { DetailsLoadingSweep() }
            compose.mainClock.advanceTimeByFrame()

            val sweep = compose.onNodeWithTag("details-loading-sweep")
            val initialLeft = sweep.fetchSemanticsNode().boundsInRoot.left
            compose.mainClock.advanceTimeBy(600)
            val movingLeft = sweep.fetchSemanticsNode().boundsInRoot.left
            assertNotEquals(initialLeft, movingLeft)

            compose.runOnIdle { SaabMotion.setReducedMotion(context, true) }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            val stationaryLeft = sweep.fetchSemanticsNode().boundsInRoot.left
            compose.mainClock.advanceTimeBy(3_000)
            assertEquals(stationaryLeft, sweep.fetchSemanticsNode().boundsInRoot.left, 0.5f)

            compose.runOnIdle { SaabMotion.setReducedMotion(context, false) }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.mainClock.advanceTimeByFrame()
            val restartedLeft = sweep.fetchSemanticsNode().boundsInRoot.left
            compose.mainClock.advanceTimeBy(600)
            assertNotEquals(restartedLeft, sweep.fetchSemanticsNode().boundsInRoot.left)
        }
    }

    @Test fun systemAnimationsDisabledKeepDetailsLoadingSweepStationary() {
        withSystemAnimationsEnabled(false) {
            assertEquals(false, SaabMotion.animationsEnabled)
            compose.mainClock.autoAdvance = false
            compose.setContent { DetailsLoadingSweep() }
            val sweep = compose.onNodeWithTag("details-loading-sweep")
            val initialLeft = sweep.fetchSemanticsNode().boundsInRoot.left

            compose.mainClock.advanceTimeBy(3_000)

            assertEquals(initialLeft, sweep.fetchSemanticsNode().boundsInRoot.left, 0.5f)
        }
    }

    @Test fun sharedNavigationTransitionsRespectReducedMotion() {
        withReducedMotion(true) {
            assertEquals(0,
                (SaabMotion.navigationRevealSpec<Float>() as TweenSpec<Float>).durationMillis)
        }
        withReducedMotion(false) {
            assertEquals(SaabMotion.NAVIGATION_REVEAL_MS,
                (SaabMotion.navigationRevealSpec<Float>() as TweenSpec<Float>).durationMillis)
        }
    }

    private fun withReducedMotion(enabled: Boolean, test: () -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        val previous = SaabMotion.isReducedMotion
        SaabMotion.setReducedMotion(context, enabled)
        try {
            test()
        } finally {
            SaabMotion.setReducedMotion(context, previous)
            compose.mainClock.autoAdvance = true
        }
    }

    private fun withSystemAnimationsEnabled(enabled: Boolean, test: () -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        val previous = SaabMotion.isReducedMotion
        SaabMotion.setReducedMotion(context, false)
        SaabMotion.setSystemAnimationsEnabledForTesting(enabled)
        try {
            test()
        } finally {
            SaabMotion.setReducedMotion(context, previous)
            SaabMotion.refreshSystemAnimations()
            compose.mainClock.autoAdvance = true
        }
    }
}
