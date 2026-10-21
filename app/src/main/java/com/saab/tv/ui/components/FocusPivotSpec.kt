package com.saab.tv.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import kotlin.math.abs

/** Shared interruptible shelf motion with either a leading or centered item anchor. */
@OptIn(ExperimentalFoundationApi::class)
class FocusPivotSpec(
    private val pivotFraction: Float = 0.1f,
    private val customOffset: Float? = null,
    private val skipScrollProvider: (() -> Boolean)? = null,
    private val itemPivotFraction: Float = 0f
) : BringIntoViewSpec {
    companion object {
        const val DEFAULT_STIFFNESS = SaabMotion.FOCUS_SCROLL_STIFFNESS
    }

    // Keep scroll travel no-bounce and retargetable when the remote repeats or reverses.
    @Deprecated("", level = DeprecationLevel.HIDDEN)
    override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float>
        get() = SaabMotion.focusRetargetSpec()

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        if (skipScrollProvider?.invoke() == true) return 0f

        val targetOffset = customOffset ?: (containerSize * pivotFraction - size * itemPivotFraction)
        val scrollDelta = offset - targetOffset
        return if (abs(scrollDelta) < 1f) 0f else scrollDelta
    }
}
