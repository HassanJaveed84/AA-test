package io.github.hassanjaveed84.aatube.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderPipelineTest {

    private val base = PipelineSample(
        surface = true, displayAttached = true, webViewVisible = true, gateOpen = true,
        nativeDraws = 0, pageFps = null, mediaAdvancedSeconds = null, presentedFrames = null,
        mediaRequests = 0, statsAgeMillis = null,
    )

    @Test fun noSurface() {
        assertEquals(PipelineLevel.NO_SURFACE, base.copy(surface = false).level)
        assertEquals(PipelineLevel.NO_SURFACE, base.copy(displayAttached = false, nativeDraws = 60).level)
    }

    @Test fun surfaceHeldButIdle() = assertEquals(PipelineLevel.SURFACE_ONLY, base.level)

    @Test fun drawingWithoutMedia() =
        assertEquals(PipelineLevel.APP_DRAWING, base.copy(nativeDraws = 1, pageFps = 60, mediaAdvancedSeconds = 0.0).level)

    @Test fun mediaTimeAdvancing() =
        assertEquals(PipelineLevel.MEDIA_DECODING, base.copy(nativeDraws = 30, mediaAdvancedSeconds = 1.0).level)

    @Test fun presentedFramesCount() =
        assertEquals(PipelineLevel.MEDIA_DECODING, base.copy(presentedFrames = 24).level)

    @Test fun smallJitterIsNotProgress() =
        assertEquals(PipelineLevel.APP_DRAWING, base.copy(nativeDraws = 5, mediaAdvancedSeconds = 0.08).level)

    @Test fun progressWhileGateClosedIsAViolation() {
        val sample = base.copy(gateOpen = false, webViewVisible = false, mediaAdvancedSeconds = 1.0)
        assertTrue(sample.gateViolation)
        assertTrue(sample.format().startsWith("level=MEDIA_DECODING GATE_VIOLATION"))
    }

    @Test fun cardHeartbeatWhileClosedIsFine() {
        val sample = base.copy(gateOpen = false, webViewVisible = false, nativeDraws = 1, mediaAdvancedSeconds = 0.0)
        assertFalse(sample.gateViolation)
        assertEquals(PipelineLevel.APP_DRAWING, sample.level)
    }

    @Test fun formatIsGreppable() = assertEquals(
        "level=MEDIA_DECODING surface=yes attached=yes webview=visible gate=open draws=58/s pageFps=60 media=+1.00s frames=- segments=2 statsAge=300ms",
        base.copy(nativeDraws = 58, pageFps = 60, mediaAdvancedSeconds = 1.0, mediaRequests = 2, statsAgeMillis = 300).format(),
    )
}
