package com.saab.tv.ui.components

import android.animation.ValueAnimator
import android.content.Context
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.mutableStateOf

/** Shared motion timings for the high-frequency TV focus and browse transitions. */
object SaabMotion {
    const val FOCUS_FEEDBACK_MS = 120
    const val FOCUS_LABEL_MS = 180
    const val NAVIGATION_REVEAL_MS = 240
    const val HERO_BACKDROP_IN_MS = 240
    const val HERO_BACKDROP_OUT_MS = 200
    const val HERO_INFO_IN_MS = 160
    const val HERO_INFO_OUT_MS = 120
    const val FOCUS_SCROLL_STIFFNESS = 600f

    private const val PREFERENCES = "ui_preferences"
    private const val REDUCED_MOTION_KEY = "reduced_motion"
    private val reducedMotionState = mutableStateOf(false)
    private val systemAnimationsEnabledState = mutableStateOf(true)

    val isReducedMotion: Boolean get() = reducedMotionState.value
    val animationsEnabled: Boolean
        get() = shouldAnimate(isReducedMotion, systemAnimationsEnabledState.value)

    internal fun shouldReduceMotion(userPreference: Boolean, systemAnimationsEnabled: Boolean): Boolean =
        userPreference || !systemAnimationsEnabled

    internal fun shouldAnimate(userPreference: Boolean, systemAnimationsEnabled: Boolean): Boolean =
        !shouldReduceMotion(userPreference, systemAnimationsEnabled)

    fun initialize(context: Context) {
        reducedMotionState.value = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(REDUCED_MOTION_KEY, false)
        refreshSystemAnimations()
    }

    /** Refresh outside animation-spec evaluation so plain JVM unit tests need no Android runtime. */
    fun refreshSystemAnimations() {
        systemAnimationsEnabledState.value = ValueAnimator.areAnimatorsEnabled()
    }

    internal fun setSystemAnimationsEnabledForTesting(enabled: Boolean) {
        systemAnimationsEnabledState.value = enabled
    }

    fun setReducedMotion(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(REDUCED_MOTION_KEY, enabled)
            .apply()
        reducedMotionState.value = enabled
    }

    fun duration(defaultDurationMillis: Int): Int =
        if (!animationsEnabled) 0
        else defaultDurationMillis

    fun <T> focusRetargetSpec(reducedMotion: Boolean = isReducedMotion): AnimationSpec<T> =
        if (!shouldAnimate(reducedMotion, systemAnimationsEnabledState.value)) snap()
        else spring(
            stiffness = FOCUS_SCROLL_STIFFNESS,
            dampingRatio = Spring.DampingRatioNoBouncy
        )

    fun <T> focusFeedbackSpec(): FiniteAnimationSpec<T> = tween(
        durationMillis = duration(FOCUS_FEEDBACK_MS),
        easing = FastOutSlowInEasing
    )

    fun <T> focusLabelSpec(): AnimationSpec<T> = tween(
        durationMillis = duration(FOCUS_LABEL_MS),
        easing = FastOutSlowInEasing
    )

    fun <T> navigationRevealSpec(): FiniteAnimationSpec<T> = tween(
        durationMillis = duration(NAVIGATION_REVEAL_MS),
        easing = FastOutSlowInEasing
    )
}

/** Use the same reduced-motion policy for programmatic focus/selection scrolling everywhere. */
suspend fun LazyListState.scrollToItemUsingSaabMotion(index: Int, scrollOffset: Int = 0) {
    if (SaabMotion.animationsEnabled) animateScrollToItem(index, scrollOffset)
    else scrollToItem(index, scrollOffset)
}

suspend fun LazyGridState.scrollToItemUsingSaabMotion(index: Int, scrollOffset: Int = 0) {
    if (SaabMotion.animationsEnabled) animateScrollToItem(index, scrollOffset)
    else scrollToItem(index, scrollOffset)
}
