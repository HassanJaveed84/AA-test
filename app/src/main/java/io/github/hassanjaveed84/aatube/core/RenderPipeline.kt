package io.github.hassanjaveed84.aatube.core

/**
 * How far down the rendering pipeline there is evidence of activity, from
 * the app's side. What the head unit actually displays is outside the app's
 * view; that last step is proven only by host screenshots (DHU) or by eye.
 */
enum class PipelineLevel {
    /** No car surface, or the virtual display is not attached to it. */
    NO_SURFACE,

    /** Surface held, but nothing has been drawn into it this second. */
    SURFACE_ONLY,

    /** The app drew into the surface (status card or web content), but no media progressed. */
    APP_DRAWING,

    /** Media time advanced or video frames were presented in the WebView. */
    MEDIA_DECODING,
}

/** One second of pipeline counters, assembled by the car-screen player. */
data class PipelineSample(
    val surface: Boolean,
    val displayAttached: Boolean,
    val webViewVisible: Boolean,
    val gateOpen: Boolean,
    /** Draw passes of the view tree on the virtual display. */
    val nativeDraws: Int,
    /** requestAnimationFrame callbacks in the top page, if a page reported. */
    val pageFps: Int?,
    /** Media seconds advanced since the previous report. */
    val mediaAdvancedSeconds: Double?,
    /** Video frames presented (requestVideoFrameCallback), where measurable. */
    val presentedFrames: Int?,
    /** Media segment requests (googlevideo /videoplayback) seen this second. */
    val mediaRequests: Int,
    /** Age of the page's last stats report. */
    val statsAgeMillis: Long?,
) {
    val mediaProgressing: Boolean
        get() = (mediaAdvancedSeconds ?: 0.0) >= 0.5 || (presentedFrames ?: 0) > 0

    val level: PipelineLevel
        get() = when {
            !surface || !displayAttached -> PipelineLevel.NO_SURFACE
            mediaProgressing -> PipelineLevel.MEDIA_DECODING
            nativeDraws > 0 -> PipelineLevel.APP_DRAWING
            else -> PipelineLevel.SURFACE_ONLY
        }

    /** Media progressing while the gate is closed: the gate failed. */
    val gateViolation: Boolean get() = !gateOpen && mediaProgressing

    fun format(): String = buildString {
        append("level=").append(level)
        if (gateViolation) append(" GATE_VIOLATION")
        append(" surface=").append(yes(surface))
        append(" attached=").append(yes(displayAttached))
        append(" webview=").append(if (webViewVisible) "visible" else "hidden")
        append(" gate=").append(if (gateOpen) "open" else "closed")
        append(" draws=").append(nativeDraws).append("/s")
        append(" pageFps=").append(pageFps ?: "-")
        append(" media=").append(mediaAdvancedSeconds?.let { "+" + "%.2f".format(java.util.Locale.ROOT, it) + "s" } ?: "-")
        append(" frames=").append(presentedFrames ?: "-")
        append(" segments=").append(mediaRequests)
        append(" statsAge=").append(statsAgeMillis?.let { "${it}ms" } ?: "-")
    }

    private fun yes(value: Boolean) = if (value) "yes" else "no"
}
