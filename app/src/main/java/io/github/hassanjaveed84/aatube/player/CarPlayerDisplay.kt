package io.github.hassanjaveed84.aatube.player

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.hassanjaveed84.aatube.BuildConfig
import io.github.hassanjaveed84.aatube.core.AppEvent
import io.github.hassanjaveed84.aatube.core.AppHub
import io.github.hassanjaveed84.aatube.core.AppState
import io.github.hassanjaveed84.aatube.core.GateState
import io.github.hassanjaveed84.aatube.core.KeyScroll
import io.github.hassanjaveed84.aatube.core.PlayerCommand
import io.github.hassanjaveed84.aatube.core.PlayerError
import io.github.hassanjaveed84.aatube.core.PlayerMode
import io.github.hassanjaveed84.aatube.core.PlayerStatus
import io.github.hassanjaveed84.aatube.core.StatusText
import io.github.hassanjaveed84.aatube.core.VideoRef
import io.github.hassanjaveed84.aatube.core.WebViewport

/**
 * Draws the player on the car display: the host's Surface backs a private
 * VirtualDisplay, and a Presentation on it holds the WebView plus an opaque
 * status card. (The VirtualDisplay + Presentation technique comes from AA
 * Suite, MIT License.)
 *
 * The status card is never left blank. While the parked-only gate is closed
 * the WebView is hidden and every video in it is paused and muted, taps are
 * not forwarded, and a page is never loaded for the first time.
 */
object CarPlayerDisplay {

    private const val TAG = "CarPlayerDisplay"
    private const val ENFORCE_INTERVAL_MS = 1_000L
    private const val TV_DEEP_LINK_RETRY_MS = 4_000L
    private const val TV_DEEP_LINK_ATTEMPTS = 3
    private const val GESTURE_IDLE_MS = 140L

    private val main = Handler(Looper.getMainLooper())

    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var webView: WebView? = null
    private var card: TextView? = null
    private var labStrip: TextView? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    /** What the WebView currently holds, so state updates only reload on change. */
    private data class PageKey(val mode: PlayerMode, val videoId: String?, val start: Int, val seq: Int)
    private var loadedKey: PageKey? = null
    private var blocked = true
    private var heartbeat = 0L
    private var lastStats: String = ""
    private var tvDeepLinkAttempts = 0
    private val keyScroll = KeyScroll()

    private val enforcer = object : Runnable {
        override fun run() {
            if (!blocked) return
            webView?.evaluateJavascript(PlayerPages.setBlockedJs(true), null)
            heartbeat++
            if (BuildConfig.LAB) updateCard(AppHub.state.value)
            main.postDelayed(this, ENFORCE_INTERVAL_MS)
        }
    }

    val isAttached: Boolean get() = presentation != null

    fun attach(context: Context, surface: Surface, width: Int, height: Int, dpi: Int) {
        main.post {
            detachNow()
            surfaceWidth = width
            surfaceHeight = height
            val manager = context.getSystemService(DisplayManager::class.java)
            val display = manager.createVirtualDisplay(
                "AATube", width, height, dpi, surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            )
            val shown = Presentation(context, display.display)
            val root = FrameLayout(shown.context).apply { setBackgroundColor(Color.BLACK) }
            val statusCard = TextView(shown.context).apply {
                setBackgroundColor(Color.BLACK)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                textSize = 22f
                setPadding(48, 48, 48, 48)
            }
            root.addView(statusCard, matchParent())
            if (BuildConfig.LAB) {
                val strip = TextView(shown.context).apply {
                    setBackgroundColor(0xB0000000.toInt())
                    setTextColor(0xFF7CFC00.toInt())
                    textSize = 11f
                    setPadding(12, 4, 12, 4)
                }
                root.addView(
                    strip,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM),
                )
                labStrip = strip
            }
            shown.setContentView(root)
            shown.show()
            virtualDisplay = display
            presentation = shown
            card = statusCard
            AppHub.record("surface", "display attached ${width}x$height@$dpi")
            render(AppHub.state.value)
        }
    }

    fun detach() {
        main.post { detachNow() }
    }

    private fun detachNow() {
        if (presentation == null && virtualDisplay == null) return
        main.removeCallbacks(enforcer)
        main.removeCallbacks(tvDeepLinkRetry)
        keyScroll.reset()
        webView?.let { it.stopLoading(); it.destroy() }
        presentation?.dismiss()
        virtualDisplay?.release()
        webView = null
        presentation = null
        virtualDisplay = null
        card = null
        labStrip = null
        loadedKey = null
        AppHub.record("surface", "display detached")
    }

    /** Applies the latest app state: gate first, then the page to show. */
    fun render(state: AppState) {
        main.post { renderNow(state) }
    }

    private fun renderNow(state: AppState) {
        if (presentation == null) return
        val open = state.gate is GateState.Open
        if (!open) {
            block()
        }
        val wanted = wantedPage(state)
        if (open && wanted != null && wanted != loadedKey) {
            load(wanted, state.video)
        }
        if (wanted == null && loadedKey != null) unload()
        if (open) unblock()
        updateCard(state)
    }

    private fun wantedPage(state: AppState): PageKey? = when (state.mode) {
        PlayerMode.DIRECT -> state.video?.let { PageKey(PlayerMode.DIRECT, it.id, it.startSeconds, state.requestSeq) }
        PlayerMode.YOUTUBE_TV ->
            PageKey(PlayerMode.YOUTUBE_TV, state.video?.id, state.video?.startSeconds ?: 0, state.requestSeq)
        PlayerMode.TEST_PATTERN -> PageKey(PlayerMode.TEST_PATTERN, null, 0, 0)
    }

    private fun block() {
        if (!blocked) {
            blocked = true
            AppHub.record("display", "video hidden and paused (gate closed)")
        }
        webView?.let {
            it.evaluateJavascript(PlayerPages.setBlockedJs(true), null)
            it.visibility = View.INVISIBLE
        }
        main.removeCallbacks(enforcer)
        main.post(enforcer)
    }

    private fun unblock() {
        if (!blocked) return
        blocked = false
        main.removeCallbacks(enforcer)
        webView?.let {
            it.evaluateJavascript(PlayerPages.setBlockedJs(false), null)
            it.visibility = View.VISIBLE
        }
        AppHub.record("display", "video visible (gate open); playback resumes only on user request")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun load(key: PageKey, video: VideoRef?) {
        val shown = presentation ?: return
        val web = webView ?: createWebView(shown).also { webView = it }
        val root = web.parent as? FrameLayout
        if (root == null) (card?.parent as? FrameLayout)?.addView(web, 0, matchParent())
        configureFor(web, key.mode)
        loadedKey = key
        tvDeepLinkAttempts = 0
        AppHub.dispatch(AppEvent.PlayerReported(PlayerStatus.Loading))
        when (key.mode) {
            PlayerMode.DIRECT -> {
                val html = PlayerPages.directHtml(video!!, surfaceWidth, surfaceHeight, blocked)
                web.loadDataWithBaseURL(
                    PlayerPages.directBaseUrl(BuildConfig.APPLICATION_ID), html, "text/html", "UTF-8", null,
                )
            }
            PlayerMode.YOUTUBE_TV -> web.loadUrl(PlayerPages.tvUrl(video))
            PlayerMode.TEST_PATTERN -> web.loadUrl(PlayerPages.TEST_PATTERN_URL)
        }
        AppHub.record("display", "load ${key.mode}")
    }

    private fun unload() {
        webView?.let {
            it.stopLoading()
            it.loadUrl("about:blank")
        }
        loadedKey = null
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun createWebView(shown: Presentation): WebView = WebView(shown.context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        setBackgroundColor(Color.BLACK)
        visibility = if (blocked) View.INVISIBLE else View.VISIBLE
        addJavascriptInterface(Bridge, PlayerPages.BRIDGE_NAME)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(this, PlayerPages.TV_CONTROL_SCRIPT, setOf("https://www.youtube.com"))
        }
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (loadedKey?.mode == PlayerMode.YOUTUBE_TV) {
                    view.evaluateJavascript("window.__aatubeBlocked=$blocked;" + PlayerPages.TV_CONTROL_SCRIPT, null)
                    scheduleTvDeepLink()
                    // The TV UI reports states only once a video element plays.
                    if (AppHub.state.value.player == PlayerStatus.Loading) {
                        AppHub.dispatch(AppEvent.PlayerReported(PlayerStatus.Idle))
                    }
                }
                view.evaluateJavascript(PlayerPages.setBlockedJs(blocked), null)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    AppHub.record("player", "main frame error ${error.errorCode}")
                    AppHub.dispatch(AppEvent.PlayerReported(PlayerStatus.Failed(PlayerError.NETWORK)))
                }
            }
        }
    }

    private fun configureFor(web: WebView, mode: PlayerMode) {
        val tv = mode == PlayerMode.YOUTUBE_TV
        web.settings.userAgentString = if (tv) PlayerPages.TV_USER_AGENT else null
        web.settings.allowFileAccess = mode == PlayerMode.TEST_PATTERN
        val scale = if (tv) WebViewport.initialScalePercent(surfaceWidth, PlayerPages.TV_LOGICAL_WIDTH) else 100
        web.settings.useWideViewPort = scale != 100
        web.settings.loadWithOverviewMode = scale != 100
        web.setInitialScale(if (scale != 100) scale else 0)
    }

    /**
     * YouTube TV may route a fresh guest session to its account picker and
     * drop the #/watch deep link; re-apply it a few times once the page has
     * settled. Unverified on a real phone; see docs/FEASIBILITY.md.
     */
    private val tvDeepLinkRetry = Runnable {
        val id = loadedKey?.videoId ?: return@Runnable
        if (loadedKey?.mode != PlayerMode.YOUTUBE_TV) return@Runnable
        tvDeepLinkAttempts++
        webView?.evaluateJavascript(
            "(function(){if(location.hash.indexOf('#/watch')!==0){location.hash='#/watch?v=$id';return 'retry';}return 'ok';})();",
        ) { result ->
            if (result?.contains("retry") == true && tvDeepLinkAttempts < TV_DEEP_LINK_ATTEMPTS) scheduleTvDeepLink()
        }
    }

    private fun scheduleTvDeepLink() {
        if (loadedKey?.videoId == null) return
        main.removeCallbacks(tvDeepLinkRetry)
        main.postDelayed(tvDeepLinkRetry, TV_DEEP_LINK_RETRY_MS)
    }

    fun command(command: PlayerCommand) {
        main.post {
            val web = webView ?: return@post
            when (command) {
                PlayerCommand.PLAY -> if (!blocked) web.evaluateJavascript(PlayerPages.call("play()"), null)
                PlayerCommand.PAUSE -> web.evaluateJavascript(PlayerPages.call("pause()"), null)
                PlayerCommand.RELOAD -> if (!blocked) web.reload()
                PlayerCommand.BACK -> if (!blocked) web.evaluateJavascript(PlayerPages.call("back()"), null)
            }
        }
    }

    fun tap(x: Float, y: Float) {
        main.post {
            if (blocked) {
                AppHub.record("input", "tap ignored (gate closed)")
                return@post
            }
            val target = presentation?.window?.decorView ?: return@post
            val down = SystemClock.uptimeMillis()
            dispatch(target, MotionEvent.ACTION_DOWN, x, y, down)
            dispatch(target, MotionEvent.ACTION_UP, x, y, down)
        }
    }

    /** Car scroll/pan -> arrow keys, which is how YouTube TV moves focus. */
    fun scroll(distanceX: Float, distanceY: Float) {
        main.post {
            if (blocked) return@post
            val keys = keyScroll.advance(distanceX, distanceY)
            if (keys.isEmpty()) return@post
            val list = keys.joinToString(",") { "['${it.key}',${it.keyCode}]" }
            webView?.evaluateJavascript(PlayerPages.call("arrows([$list])"), null)
        }
    }

    private fun dispatch(target: View, action: Int, x: Float, y: Float, downTime: Long) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        target.dispatchTouchEvent(event)
        event.recycle()
    }

    private fun updateCard(state: AppState) {
        val statusCard = card ?: return
        val message = when {
            state.gate is GateState.Closed -> StatusText.gate(state.gate)
            state.player is PlayerStatus.Failed -> (state.player as PlayerStatus.Failed).error.userMessage
            wantedPage(state) == null -> "Choose a video on the phone: paste a YouTube link or share one to AA Tube."
            else -> null
        }
        if (message != null && BuildConfig.LAB && blocked) {
            statusCard.text = "$message\n\nlab: surface heartbeat $heartbeat"
        } else {
            statusCard.text = message ?: ""
        }
        // The card covers the WebView whenever there is something to say or video is not allowed.
        statusCard.visibility = if (message != null || blocked) View.VISIBLE else View.GONE
        if (BuildConfig.LAB) {
            labStrip?.text = "LAB · ${StatusText.gate(state.gate)} · ${StatusText.player(state.player)} · $lastStats"
        }
    }

    private fun matchParent() =
        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    /** Status reports from the page; never takes instructions from it. */
    private object Bridge {
        @JavascriptInterface
        fun onState(state: Int) {
            main.post {
                PlayerPages.statusFromState(state, blocked)?.let {
                    AppHub.dispatch(AppEvent.PlayerReported(it))
                }
            }
        }

        @JavascriptInterface
        fun onError(code: Int) {
            main.post {
                Log.w(TAG, "player error $code")
                AppHub.record("player", "error code $code")
                AppHub.dispatch(AppEvent.PlayerReported(PlayerStatus.Failed(PlayerPages.errorFromCode(code))))
            }
        }

        @JavascriptInterface
        fun onStats(json: String) {
            if (!BuildConfig.LAB) return
            main.post {
                lastStats = json.take(200)
                AppHub.record("frames", lastStats)
                labStrip?.text = "LAB · ${StatusText.gate(AppHub.state.value.gate)} · $lastStats"
            }
        }
    }
}
