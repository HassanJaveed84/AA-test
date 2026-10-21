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
import android.view.ViewTreeObserver
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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
import io.github.hassanjaveed84.aatube.core.LogRedactor
import io.github.hassanjaveed84.aatube.core.PipelineSample
import io.github.hassanjaveed84.aatube.core.PlayerCommand
import io.github.hassanjaveed84.aatube.core.PlayerError
import io.github.hassanjaveed84.aatube.core.PlayerMode
import io.github.hassanjaveed84.aatube.core.PlayerStatus
import io.github.hassanjaveed84.aatube.core.StatusText
import io.github.hassanjaveed84.aatube.core.VideoRef
import io.github.hassanjaveed84.aatube.core.WebViewport
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.atomic.AtomicInteger

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
    private const val PIPELINE_INTERVAL_MS = 1_000L
    private const val TV_STEP_MS = 2_500L
    private const val TV_MAX_STEPS = 12
    private const val STATS_FRESH_MS = 2_500L

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
    private val keyScroll = KeyScroll()

    // Pipeline counters (see core/RenderPipeline.kt).
    private val draws = AtomicInteger()
    private val mediaRequests = AtomicInteger()
    private val playerRequests = AtomicInteger()
    private var statsAt = 0L
    private var statsPageFps: Int? = null
    private var statsAdvanced: Double? = null
    private var statsFrames: Int? = null
    private val drawListener = ViewTreeObserver.OnDrawListener { draws.incrementAndGet() }

    // YouTube TV profile-picker automation.
    private var tvSteps = 0
    private var tvWaitingLogged = false

    private val enforcer = object : Runnable {
        override fun run() {
            if (!blocked) return
            webView?.evaluateJavascript(PlayerPages.setBlockedJs(true), null)
            heartbeat++
            if (BuildConfig.LAB) updateCard(AppHub.state.value)
            main.postDelayed(this, ENFORCE_INTERVAL_MS)
        }
    }

    /** Lab build: one pipeline line per second while attached. */
    private val pipelineTicker = object : Runnable {
        override fun run() {
            if (presentation == null) return
            val now = SystemClock.elapsedRealtime()
            val fresh = statsAt != 0L && now - statsAt <= STATS_FRESH_MS
            val sample = PipelineSample(
                surface = AppHub.state.value.surface != null,
                displayAttached = presentation != null,
                webViewVisible = webView?.visibility == View.VISIBLE,
                gateOpen = !blocked,
                nativeDraws = draws.getAndSet(0),
                pageFps = if (fresh) statsPageFps else null,
                mediaAdvancedSeconds = if (fresh) statsAdvanced else null,
                presentedFrames = if (fresh) statsFrames else null,
                mediaRequests = mediaRequests.getAndSet(0),
                statsAgeMillis = if (statsAt == 0L) null else now - statsAt,
            )
            AppHub.record("pipeline", sample.format())
            if (sample.gateViolation) AppHub.record("gate", "WARNING: media progressing while the gate is closed")
            val players = playerRequests.getAndSet(0)
            if (players > 0) AppHub.record("pipeline", "youtube player requests: $players")
            main.postDelayed(this, PIPELINE_INTERVAL_MS)
        }
    }

    private val tvAutoContinue = object : Runnable {
        override fun run() {
            val web = webView ?: return
            if (loadedKey?.mode != PlayerMode.YOUTUBE_TV) return
            if (blocked) {
                // No automated input while the gate is closed; resume afterwards.
                main.postDelayed(this, TV_STEP_MS)
                return
            }
            if (tvSteps >= TV_MAX_STEPS) {
                AppHub.record("tv", "auto-continue stopped after $tvSteps steps; use the car screen to continue")
                return
            }
            tvSteps++
            web.evaluateJavascript(PlayerPages.TV_NEXT_STEP_JS) { raw -> handleTvStep(raw) }
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
            root.viewTreeObserver.addOnDrawListener(drawListener)
            shown.setContentView(root)
            shown.show()
            virtualDisplay = display
            presentation = shown
            card = statusCard
            AppHub.record("surface", "display attached ${width}x$height@$dpi")
            if (BuildConfig.LAB) main.postDelayed(pipelineTicker, PIPELINE_INTERVAL_MS)
            render(AppHub.state.value)
        }
    }

    fun detach() {
        main.post { detachNow() }
    }

    private fun detachNow() {
        if (presentation == null && virtualDisplay == null) return
        main.removeCallbacks(enforcer)
        main.removeCallbacks(tvAutoContinue)
        main.removeCallbacks(pipelineTicker)
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
        statsAt = 0L
        AppHub.record("surface", "display detached")
    }

    /** Applies the latest app state: gate first, then the page to show. */
    fun render(state: AppState) {
        main.post { renderNow(state) }
    }

    private fun renderNow(state: AppState) {
        if (presentation == null) return
        val open = state.gate is GateState.Open
        // Settle the gate before any load, so a new page is generated with the
        // right blocked flag (otherwise autoplay races the page's callbacks).
        if (open) unblock() else block()
        val wanted = wantedPage(state)
        if (open && wanted != null && wanted != loadedKey) {
            load(wanted, state.video)
        }
        if (wanted == null && loadedKey != null) unload()
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
        if (web.parent == null) (card?.parent as? FrameLayout)?.addView(web, 0, matchParent())
        configureFor(web, key.mode)
        loadedKey = key
        main.removeCallbacks(tvAutoContinue)
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
        AppHub.record("display", "load ${key.mode}" + (key.videoId?.let { " id=${LogRedactor.videoId(it)}" } ?: ""))
    }

    private fun unload() {
        main.removeCallbacks(tvAutoContinue)
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
        } else {
            AppHub.record("display", "WebView lacks DOCUMENT_START_SCRIPT; TV script installed on page finish only")
        }
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (loadedKey?.mode == PlayerMode.YOUTUBE_TV) {
                    view.evaluateJavascript("window.__aatubeBlocked=$blocked;" + PlayerPages.TV_CONTROL_SCRIPT, null)
                    startTvAutoContinue()
                    // The TV UI reports states only once a video element plays.
                    if (AppHub.state.value.player == PlayerStatus.Loading) {
                        AppHub.dispatch(AppEvent.PlayerReported(PlayerStatus.Idle))
                    }
                }
                view.evaluateJavascript(PlayerPages.setBlockedJs(blocked), null)
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                // Counting only; every request proceeds untouched.
                val url = request.url
                val host = url.host.orEmpty()
                val path = url.path.orEmpty()
                if (host.endsWith("googlevideo.com") && path.contains("/videoplayback")) mediaRequests.incrementAndGet()
                if (path.contains("/youtubei/v1/player")) playerRequests.incrementAndGet()
                return null
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
     * YouTube TV routes a fresh session through "Get started" and its profile
     * picker and drops the #/watch deep link until a profile is chosen. The
     * page script reports where the guest/start entry is; it is tapped with a
     * real MotionEvent (the TV UI ignores scripted clicks), then the deep link
     * is re-applied until the video plays. Verified in Chromium; see
     * tools/pagecheck/tv-flow.js.
     */
    private fun startTvAutoContinue() {
        tvSteps = 0
        tvWaitingLogged = false
        main.removeCallbacks(tvAutoContinue)
        main.postDelayed(tvAutoContinue, TV_STEP_MS)
    }

    private fun handleTvStep(raw: String?) {
        val text = runCatching { JSONTokener(raw ?: "null").nextValue() as? String }.getOrNull()
        if (text == null) {
            main.postDelayed(tvAutoContinue, TV_STEP_MS)
            return
        }
        val step = runCatching { JSONObject(text) }.getOrNull() ?: return
        val hash = step.optString("hash")
        val playing = step.optBoolean("playing")
        val videoId = loadedKey?.videoId
        when (step.optString("action")) {
            "tap" -> {
                val target = step.getJSONObject("target")
                tapFraction(target.getDouble("x"), target.getDouble("y"))
                AppHub.record("tv", "tapped '${target.optString("label")}' (step $tvSteps)")
            }
            "wait" -> if (!tvWaitingLogged) {
                tvWaitingLogged = true
                AppHub.record("tv", "profile picker lists ${step.optInt("profiles")} signed-in profile(s); choose one on the car screen")
            }
            else -> when {
                videoId != null && !hash.startsWith("#/watch?v=$videoId") -> {
                    webView?.evaluateJavascript(PlayerPages.tvSetWatchHashJs(videoId), null)
                    AppHub.record("tv", "re-applied deep link (step $tvSteps, page at '${hash.take(12)}')")
                }
                playing || videoId == null -> {
                    AppHub.record("tv", if (playing) "deep-linked video playing after $tvSteps step(s)" else "TV home ready")
                    return
                }
            }
        }
        main.postDelayed(tvAutoContinue, TV_STEP_MS)
    }

    private fun tapFraction(fx: Double, fy: Double) {
        val web = webView ?: return
        val target = presentation?.window?.decorView ?: return
        val x = (fx * web.width).toFloat().coerceIn(0f, web.width.toFloat())
        val y = (fy * web.height).toFloat().coerceIn(0f, web.height.toFloat())
        val down = SystemClock.uptimeMillis()
        dispatch(target, MotionEvent.ACTION_DOWN, x, y, down)
        dispatch(target, MotionEvent.ACTION_UP, x, y, down)
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

    /**
     * Embedded-player refusals (101/150/152): record what oEmbed says about
     * the video, then retry the same video in YouTube TV mode, which uses
     * YouTube's TV client rather than the embedded player.
     */
    private fun onEmbedRefused(code: Int) {
        val key = loadedKey ?: return
        val id = key.videoId ?: return
        if (key.mode != PlayerMode.DIRECT) return
        EmbedCheck.check(id) { status, http ->
            AppHub.record("player", "embed diagnosis for ${LogRedactor.videoId(id)}: error $code, oEmbed HTTP ${http ?: "n/a"} -> ${status.explanation}")
        }
        AppHub.record("player", "embedded player refused (error $code); retrying in YouTube TV mode")
        AppHub.dispatch(AppEvent.ModeSelected(PlayerMode.YOUTUBE_TV))
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
                val error = PlayerPages.errorFromCode(code)
                AppHub.dispatch(AppEvent.PlayerReported(PlayerStatus.Failed(error)))
                if (error == PlayerError.EMBED_BLOCKED) onEmbedRefused(code)
            }
        }

        @JavascriptInterface
        fun onStats(json: String) {
            if (!BuildConfig.LAB) return
            main.post {
                lastStats = json.take(200)
                runCatching { JSONObject(json) }.getOrNull()?.let { stats ->
                    statsAt = SystemClock.elapsedRealtime()
                    statsPageFps = if (stats.has("pageFps")) stats.optInt("pageFps") else null
                    statsAdvanced = if (stats.has("advanced")) stats.optDouble("advanced") else null
                    // TV: presented video frames; test pattern: animation frames while playing.
                    statsFrames = if (stats.has("fps")) stats.optInt("fps") else null
                }
                AppHub.record("frames", lastStats)
                labStrip?.text = "LAB · ${StatusText.gate(AppHub.state.value.gate)} · $lastStats"
            }
        }
    }
}
