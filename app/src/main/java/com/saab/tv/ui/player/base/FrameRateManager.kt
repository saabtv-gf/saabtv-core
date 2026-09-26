package com.saab.tv.ui.player.base

import android.app.Activity
import android.os.Build
import android.util.Log
import android.view.Display
import kotlin.math.abs

/**
 * Manages display refresh rate switching to match video frame rate.
 * Snaps to standard rates, picks exact → 2x → 2.5x → fallback,
 * and restores the original mode on release.
 */
class FrameRateManager(private val activity: Activity) {

    private var originalModeId: Int? = null

    /**
     * Snaps a raw detected frame rate to the nearest well-known standard.
     * Containers/codecs often report slightly imprecise values
     * (e.g. 23.98 instead of 23.976).
     */
    fun snapToStandardRate(raw: Float): Float = when {
        raw in 23.90f..23.988f  -> NTSC_FILM_FPS      // 23.976
        raw in 23.988f..24.1f   -> 24f
        raw in 24.9f..25.1f     -> 25f
        raw in 29.90f..29.985f  -> NTSC_30_FPS         // 29.97
        raw in 29.985f..30.1f   -> 30f
        raw in 49.9f..50.1f     -> 50f
        raw in 59.9f..59.97f    -> NTSC_60_FPS         // 59.94
        raw in 59.97f..60.1f    -> 60f
        else                    -> raw
    }

    /**
     * Switches the display to the best mode for the given video frame rate.
     * Returns true if a mode switch was requested.
     */
    fun matchDisplayToFrameRate(videoFrameRate: Float): Boolean {
        if (videoFrameRate < MIN_VALID_FPS || videoFrameRate > MAX_VALID_FPS) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false

        val window = activity.window ?: return false
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display
        } else {
            @Suppress("DEPRECATION")
            window.windowManager.defaultDisplay
        } ?: return false

        val currentMode = display.mode
        if (originalModeId == null) {
            originalModeId = currentMode.modeId
        }

        val snapped = snapToStandardRate(videoFrameRate)
        val bestMode = chooseBestMode(display, currentMode, snapped)

        if (bestMode == null || bestMode.modeId == currentMode.modeId) {
            Log.d(TAG, "No better display mode for ${snapped}fps (current: ${currentMode.refreshRate}Hz)")
            return false
        }

        Log.d(TAG, "Switching: ${currentMode.refreshRate}Hz -> ${bestMode.refreshRate}Hz for ${snapped}fps video")
        val params = window.attributes
        params.preferredDisplayModeId = bestMode.modeId
        window.attributes = params
        return true
    }

    fun restoreOriginalMode() {
        val targetId = originalModeId ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

        val window = activity.window ?: return
        Log.d(TAG, "Restoring original display mode (id=$targetId)")
        val params = window.attributes
        params.preferredDisplayModeId = targetId
        window.attributes = params
        originalModeId = null
    }

    // ---- mode selection (exact → 2x → 2.5x pulldown → weighted fallback) ----

    private fun chooseBestMode(
        display: Display,
        currentMode: Display.Mode,
        frameRate: Float
    ): Display.Mode? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null

        val sameSizeModes = display.supportedModes.filter {
            it.physicalWidth == currentMode.physicalWidth &&
                it.physicalHeight == currentMode.physicalHeight
        }
        if (sameSizeModes.isEmpty()) return null

        // Priority: exact match > 2x > 2.5x (3:2 pulldown) > closest weighted
        val exact    = pickClosest(sameSizeModes, frameRate)
        val double   = pickClosest(sameSizeModes, frameRate * 2f)
        val pulldown = pickClosest(sameSizeModes, frameRate * 2.5f)
        val fallback = sameSizeModes.minByOrNull { refreshWeight(it.refreshRate, frameRate) }

        return exact ?: double ?: pulldown ?: fallback ?: currentMode
    }

    private fun pickClosest(modes: List<Display.Mode>, target: Float): Display.Mode? {
        if (target <= 0f) return null
        val closest = modes.minByOrNull { abs(it.refreshRate - target) } ?: return null
        return if (matchesTarget(closest.refreshRate, target)) closest else null
    }

    private fun matchesTarget(actual: Float, target: Float): Boolean {
        val tolerance = maxOf(REFRESH_TOLERANCE_HZ, target * REFRESH_TOLERANCE_FRACTION)
        return abs(actual - target) <= tolerance
    }

    /** Weight function for fallback: lower = better. Penalises non-multiples. */
    private fun refreshWeight(refreshRate: Float, frameRate: Float): Float {
        if (refreshRate < frameRate - REFRESH_TOLERANCE_HZ) return Float.MAX_VALUE
        val ratio = refreshRate / frameRate
        val nearest = Math.round(ratio).toFloat()
        if (nearest < 1f) return Float.MAX_VALUE
        val remainder = abs(ratio - nearest)
        return remainder + nearest * 0.001f
    }

    companion object {
        private const val TAG = "FrameRateManager"
        private const val NTSC_FILM_FPS = 24000f / 1001f   // 23.976
        private const val NTSC_30_FPS = 30000f / 1001f      // 29.97
        private const val NTSC_60_FPS = 60000f / 1001f      // 59.94
        private const val REFRESH_TOLERANCE_HZ = 0.08f
        private const val REFRESH_TOLERANCE_FRACTION = 0.003f
        private const val MIN_VALID_FPS = 10f
        private const val MAX_VALID_FPS = 120f
    }
}
