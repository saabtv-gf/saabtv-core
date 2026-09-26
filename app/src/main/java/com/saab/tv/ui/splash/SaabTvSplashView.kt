package com.saab.tv.ui.splash

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.VideoView
import com.saab.tv.R

/** Plays the packaged Saab TV intro and falls back to the supplied static logo. */
class SaabTvSplashView(
    context: Context,
    private val onIntroFinished: () -> Unit
) : FrameLayout(context) {
    private val fallbackLogo = ImageView(context).apply {
        setImageResource(R.drawable.saab_tv_wordmark)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setBackgroundColor(Color.BLACK)
    }
    private val video = VideoView(context).apply {
        setBackgroundColor(Color.TRANSPARENT)
    }
    private var completionDispatched = false
    private var stopped = false

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = true
        isFocusable = true

        addView(
            fallbackLogo,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.CENTER
            }
        )
        addView(
            video,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.CENTER
            }
        )

        video.setOnPreparedListener { player ->
            if (stopped) return@setOnPreparedListener
            player.isLooping = false
            fallbackLogo.visibility = INVISIBLE
            video.start()
        }
        video.setOnCompletionListener {
            dispatchCompletion()
        }
        video.setOnErrorListener { _, _, _ ->
            showFallbackAndComplete()
            true
        }
    }

    fun start() {
        stopped = false
        completionDispatched = false
        alpha = 1f
        fallbackLogo.visibility = VISIBLE
        video.visibility = VISIBLE
        video.setVideoURI(
            Uri.parse("android.resource://${context.packageName}/${R.raw.splash_video}")
        )
        video.requestFocus()
        video.start()
    }

    fun finish(onFinished: () -> Unit) {
        animate().cancel()
        animate()
            .alpha(0f)
            .setDuration(260L)
            .withEndAction(onFinished)
            .start()
    }

    fun stop() {
        stopped = true
        animate().cancel()
        video.stopPlayback()
    }

    private fun showFallbackAndComplete() {
        video.visibility = GONE
        fallbackLogo.visibility = VISIBLE
        postDelayed(::dispatchCompletion, FALLBACK_DISPLAY_DURATION_MS)
    }

    private fun dispatchCompletion() {
        if (stopped || completionDispatched) return
        completionDispatched = true
        onIntroFinished()
    }

    private companion object {
        const val FALLBACK_DISPLAY_DURATION_MS = 1_500L
    }
}
