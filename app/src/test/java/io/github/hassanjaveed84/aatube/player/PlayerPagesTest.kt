package io.github.hassanjaveed84.aatube.player

import io.github.hassanjaveed84.aatube.core.PlayerError
import io.github.hassanjaveed84.aatube.core.PlayerStatus
import io.github.hassanjaveed84.aatube.core.VideoRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPagesTest {

    private val video = VideoRef("dQw4w9WgXcQ", 30)

    @Test fun directPageEmbedsVideoAndStart() {
        val html = PlayerPages.directHtml(video, 1920, 720, blocked = false)
        assertTrue(html.contains("videoId: 'dQw4w9WgXcQ'"))
        assertTrue(html.contains("start: 30"))
        assertTrue(html.contains("var blocked = false"))
        assertTrue(html.contains("https://www.youtube.com/iframe_api"))
    }

    @Test fun directPageStartsBlockedWhenGateClosed() =
        assertTrue(PlayerPages.directHtml(video, 800, 480, blocked = true).contains("var blocked = true"))

    @Test fun directPageIsPillarboxedOnWideSurface() {
        val html = PlayerPages.directHtml(video, 1920, 720, blocked = false)
        assertTrue(html.contains("left:16.667%;top:0.000%;width:66.667%;height:100.000%"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun directPageRejectsInjectedId() {
        PlayerPages.directHtml(VideoRef("x');alert(1"), 800, 480, blocked = false)
    }

    @Test fun baseUrlIdentifiesTheApp() =
        assertEquals("https://io.github.example/", PlayerPages.directBaseUrl("io.github.example"))

    @Test fun tvUrls() {
        assertEquals("https://www.youtube.com/tv", PlayerPages.tvUrl(null))
        assertEquals("https://www.youtube.com/tv#/watch?v=dQw4w9WgXcQ", PlayerPages.tvUrl(video))
    }

    @Test fun tvScriptHoldsVideosWhileBlocked() {
        assertTrue(PlayerPages.TV_CONTROL_SCRIPT.contains("addEventListener('play'"))
        assertTrue(PlayerPages.TV_CONTROL_SCRIPT.contains("if (blocked) vids().forEach(hold)"))
    }

    @Test fun tvScriptOffersPickerSteps() {
        assertTrue(PlayerPages.TV_CONTROL_SCRIPT.contains("nextStep: nextStep"))
        assertTrue(PlayerPages.TV_CONTROL_SCRIPT.contains("/^(Watch as guest|Guest)$/"))
        assertTrue(PlayerPages.TV_NEXT_STEP_JS.contains("window.aatube.nextStep()"))
    }

    @Test fun tvWatchHashIsGuarded() {
        assertEquals(
            "(function(){if(location.hash.indexOf('#/watch?v=dQw4w9WgXcQ')!==0){location.hash='#/watch?v=dQw4w9WgXcQ';}})();",
            PlayerPages.tvSetWatchHashJs("dQw4w9WgXcQ"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun tvWatchHashRejectsInjection() {
        PlayerPages.tvSetWatchHashJs("x';alert(1)")
    }

    @Test fun directPageCountsPageFrames() =
        assertTrue(PlayerPages.directHtml(video, 800, 480, blocked = false).contains("pageFps: pageFps"))

    @Test fun stateMapping() {
        assertEquals(PlayerStatus.Playing, PlayerPages.statusFromState(1, blocked = false))
        assertEquals(PlayerStatus.Buffering, PlayerPages.statusFromState(3, blocked = false))
        assertEquals(PlayerStatus.Ended, PlayerPages.statusFromState(0, blocked = false))
        assertEquals(PlayerStatus.Paused(byGate = true), PlayerPages.statusFromState(2, blocked = true))
        assertEquals(PlayerStatus.Paused(byGate = true), PlayerPages.statusFromState(-2, blocked = true))
        assertNull(PlayerPages.statusFromState(-2, blocked = false))
    }

    @Test fun errorMapping() {
        assertEquals(PlayerError.NETWORK, PlayerPages.errorFromCode(-1))
        assertEquals(PlayerError.EMBED_BLOCKED, PlayerPages.errorFromCode(150))
    }

    @Test fun setBlockedJsIsGuarded() =
        assertEquals(
            "window.__aatubeBlocked=true;(function(){if(window.aatube){window.aatube.setBlocked(true);}})();",
            PlayerPages.setBlockedJs(true),
        )
}

/**
 * Writes the generated pages to app/build/player-pages so they can be loaded
 * in a real browser engine (see docs/TEST_REPORT.md, "page checks").
 */
class PlayerPagesExportTest {
    @Test fun exportPages() {
        val dir = java.io.File("build/player-pages").apply { mkdirs() }
        java.io.File(dir, "direct-open.html").writeText(PlayerPages.directHtml(VideoRef("jNQXAC9IVRw"), 1920, 720, blocked = false))
        java.io.File(dir, "direct-blocked.html").writeText(PlayerPages.directHtml(VideoRef("jNQXAC9IVRw"), 1920, 720, blocked = true))
        java.io.File(dir, "tv-control.js").writeText(PlayerPages.TV_CONTROL_SCRIPT)
    }
}
