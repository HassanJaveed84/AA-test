package io.github.hassanjaveed84.aatube.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryAndInputTest {

    @Test fun sixteenNineOnWideBmwStyleDisplayIsPillarboxed() {
        // A wide central display, much wider than 16:9: bars left and right, never stretched.
        val box = AspectFit.fit(16, 9, 1920, 720)
        assertEquals(FitRect(320, 0, 1280, 720), box)
    }

    @Test fun sixteenNineOnTallerDisplayIsLetterboxed() =
        assertEquals(FitRect(0, 15, 800, 450), AspectFit.fit(16, 9, 800, 480))

    @Test fun fitKeepsAspectRatio() {
        val box = AspectFit.fit(16, 9, 1440, 540)
        assertTrue(kotlin.math.abs(box.width / box.height.toFloat() - 16f / 9f) < 0.01f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun fitRejectsEmptySurface() {
        AspectFit.fit(16, 9, 0, 480)
    }

    @Test fun tvViewportZoomsOutOnNarrowSurfaces() {
        assertEquals(62, WebViewport.initialScalePercent(800, 1280))
        assertEquals(100, WebViewport.initialScalePercent(1920, 1280))
        assertEquals(100, WebViewport.initialScalePercent(0, 1280))
    }

    @Test fun scrollBecomesArrowKeys() {
        val keys = KeyScroll()
        assertEquals(emptyList<ArrowKey>(), keys.advance(0f, 40f))
        assertEquals(listOf(ArrowKey.DOWN), keys.advance(0f, 40f))
        assertEquals(listOf(ArrowKey.LEFT), keys.advance(-90f, 0f))
    }

    @Test fun scrollPressesAreCapped() {
        assertEquals(KeyScroll.MAX_PRESSES, KeyScroll().advance(0f, 10_000f).size)
    }
}
