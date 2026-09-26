package com.saab.tv.ui.trailer

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import com.saab.tv.BuildConfig
import com.saab.tv.R

/** Fullscreen, in-app YouTube IFrame player for movie and series trailers. */
class YouTubeTrailerActivity : Activity() {

    companion object {
        private const val EXTRA_VIDEO_ID = "youtube_video_id"
        private const val EXTRA_TITLE = "trailer_title"
        private const val PLAYER_ORIGIN = "https://appassets.androidplatform.net"
        private val VIDEO_ID_REGEX = Regex("^[A-Za-z0-9_-]{11}$")

        fun createIntent(context: Context, rawVideoId: String, title: String): Intent? {
            val videoId = extractVideoId(rawVideoId) ?: return null
            return Intent(context, YouTubeTrailerActivity::class.java).apply {
                putExtra(EXTRA_VIDEO_ID, videoId)
                putExtra(EXTRA_TITLE, title)
            }
        }

        private fun extractVideoId(rawValue: String): String? {
            val value = rawValue.trim()
            if (VIDEO_ID_REGEX.matches(value)) return value

            val normalized = if (
                value.startsWith("http://", ignoreCase = true) ||
                value.startsWith("https://", ignoreCase = true)
            ) value else "https://$value"

            return runCatching {
                val uri = Uri.parse(normalized)
                if (uri.host?.lowercase()?.endsWith("youtu.be") == true) {
                    uri.pathSegments.firstOrNull()
                        ?.takeIf(VIDEO_ID_REGEX::matches)
                        ?.let { return it }
                }
                uri.getQueryParameter("v")
                    ?.takeIf(VIDEO_ID_REGEX::matches)
                    ?.let { return it }
                val segments = uri.pathSegments
                if (segments.size >= 2 && segments.first() in setOf("embed", "shorts", "live")) {
                    segments[1].takeIf(VIDEO_ID_REGEX::matches)?.let { return it }
                }
                null
            }.getOrNull()
        }
    }

    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var errorPanel: LinearLayout
    private lateinit var errorMessage: TextView
    private lateinit var videoId: String
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val startupTimeout = Runnable {
        if (::progressBar.isInitialized && progressBar.visibility == View.VISIBLE) {
            showTrailerError("The YouTube player did not start. Check your connection and try again.")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        videoId = intent.getStringExtra(EXTRA_VIDEO_ID)
            ?.takeIf(VIDEO_ID_REGEX::matches)
            ?: ""
        if (videoId.isEmpty()) {
            Toast.makeText(this, "Invalid trailer", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Trailer" }
        enterImmersiveMode()

        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        webView = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false
                cacheMode = WebSettings.LOAD_DEFAULT
                useWideViewPort = true
                loadWithOverviewMode = true
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    if (!request.url.scheme.equals("https", ignoreCase = true)) return true
                    val host = request.url.host?.lowercase().orEmpty()
                    return host != "youtube.com" &&
                        !host.endsWith(".youtube.com") &&
                        host != "youtube-nocookie.com" &&
                        !host.endsWith(".youtube-nocookie.com") &&
                        host != "consent.google.com"
                }

                override fun onPageFinished(view: WebView, url: String) {
                    view.requestFocus()
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError
                ) {
                    if (request.isForMainFrame) {
                        showTrailerError("Unable to load the trailer. Check your connection and try again.")
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: WebResourceResponse
                ) {
                    if (request.isForMainFrame) {
                        showTrailerError("YouTube returned HTTP ${errorResponse.statusCode}. Please try again.")
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    if (customView != null) {
                        callback.onCustomViewHidden()
                        return
                    }
                    customView = view
                    customViewCallback = callback
                    visibility = View.GONE
                    root.addView(
                        view,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                    enterImmersiveMode()
                }

                override fun onHideCustomView() {
                    hideCustomView()
                }
            }
            addJavascriptInterface(TrailerBridge(), "SaabTvTrailer")
        }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        progressBar = ProgressBar(this).apply {
            isIndeterminate = true
        }
        root.addView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(
            progressBar,
            FrameLayout.LayoutParams(64, 64).apply {
                gravity = android.view.Gravity.CENTER
            }
        )
        createErrorPanel()
        setContentView(root)

        loadTrailer()
    }

    private fun loadTrailer() {
        mainHandler.removeCallbacks(startupTimeout)
        errorPanel.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        webView.loadDataWithBaseURL(
            "$PLAYER_ORIGIN/",
            buildPlayerHtml(videoId),
            "text/html",
            "UTF-8",
            null
        )
        mainHandler.postDelayed(startupTimeout, 15_000L)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        return when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (customView != null) hideCustomView() else finish()
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                evaluatePlayerCommand("togglePlayback()")
                true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                evaluatePlayerCommand("playTrailer()")
                true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                evaluatePlayerCommand("pauseTrailer()")
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                evaluatePlayerCommand("seekTrailer(-10)")
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                evaluatePlayerCommand("seekTrailer(10)")
                true
            }
            else -> super.dispatchKeyEvent(event)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()
        enterImmersiveMode()
    }

    override fun onPause() {
        if (::webView.isInitialized) webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(startupTimeout)
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.clearHistory()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun evaluatePlayerCommand(command: String) {
        if (::webView.isInitialized) webView.evaluateJavascript(command, null)
    }

    private fun createErrorPanel() {
        val lato = ResourcesCompat.getFont(this, R.font.lato_regular)
        errorMessage = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = android.view.Gravity.CENTER
            typeface = lato
            setPadding(32, 24, 32, 24)
        }
        val retryButton = Button(this).apply {
            text = "Retry"
            typeface = lato
            setOnClickListener { loadTrailer() }
        }
        val backButton = Button(this).apply {
            text = "Back"
            typeface = lato
            setOnClickListener { finish() }
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            addView(retryButton)
            addView(backButton)
        }
        errorPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            addView(errorMessage)
            addView(actions)
        }
        root.addView(
            errorPanel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun showTrailerError(message: String) {
        runOnUiThread {
            mainHandler.removeCallbacks(startupTimeout)
            progressBar.visibility = View.GONE
            errorMessage.text = message
            errorPanel.visibility = View.VISIBLE
            errorPanel.getChildAt(1)?.let { actions ->
                (actions as? ViewGroup)?.getChildAt(0)?.requestFocus()
            }
        }
    }

    private inner class TrailerBridge {
        @JavascriptInterface
        fun onReady() {
            runOnUiThread {
                mainHandler.removeCallbacks(startupTimeout)
                progressBar.visibility = View.GONE
                errorPanel.visibility = View.GONE
                webView.requestFocus()
            }
        }

        @JavascriptInterface
        fun onError(code: Int) {
            val message = when (code) {
                2 -> "YouTube rejected this trailer identifier."
                5 -> "This trailer cannot be played in the embedded player."
                100 -> "This trailer was removed or made private."
                101, 150 -> "The owner does not allow this trailer to play in-app."
                else -> "YouTube could not play this trailer (error $code)."
            }
            showTrailerError(message)
        }
    }

    private fun hideCustomView() {
        val view = customView ?: return
        root.removeView(view)
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        webView.visibility = View.VISIBLE
        webView.requestFocus()
        enterImmersiveMode()
    }

    @Suppress("DEPRECATION")
    private fun enterImmersiveMode() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun buildPlayerHtml(videoId: String): String = """
        <!doctype html>
        <html>
        <head>
          <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
          <style>
            html, body, #player { width:100%; height:100%; margin:0; padding:0; overflow:hidden; background:#000; }
            iframe { width:100%; height:100%; border:0; }
          </style>
        </head>
        <body>
          <div id="player"></div>
          <script src="https://www.youtube.com/iframe_api"></script>
          <script>
            var player;
            function onYouTubeIframeAPIReady() {
              player = new YT.Player('player', {
                width: '100%', height: '100%', videoId: '$videoId',
                playerVars: {
                  autoplay: 1, controls: 1, playsinline: 1,
                  rel: 0, enablejsapi: 1, origin: '$PLAYER_ORIGIN'
                },
                events: {
                  onReady: function(e) {
                    if (window.SaabTvTrailer) window.SaabTvTrailer.onReady();
                    e.target.playVideo();
                  },
                  onError: function(e) {
                    if (window.SaabTvTrailer) window.SaabTvTrailer.onError(e.data || 0);
                  }
                }
              });
            }
            function playTrailer() { if (player && player.playVideo) player.playVideo(); }
            function pauseTrailer() { if (player && player.pauseVideo) player.pauseVideo(); }
            function togglePlayback() {
              if (!player || !player.getPlayerState) return;
              if (player.getPlayerState() === 1) player.pauseVideo(); else player.playVideo();
            }
            function seekTrailer(delta) {
              if (!player || !player.getCurrentTime) return;
              player.seekTo(Math.max(0, player.getCurrentTime() + delta), true);
            }
          </script>
        </body>
        </html>
    """.trimIndent()
}
