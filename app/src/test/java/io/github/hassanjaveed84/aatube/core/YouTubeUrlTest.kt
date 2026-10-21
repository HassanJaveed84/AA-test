package io.github.hassanjaveed84.aatube.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeUrlTest {

    private val id = "dQw4w9WgXcQ"

    @Test fun watchUrl() = assertEquals(VideoRef(id), YouTubeUrl.parse("https://www.youtube.com/watch?v=$id"))

    @Test fun mobileAndMusicHosts() {
        assertEquals(VideoRef(id), YouTubeUrl.parse("https://m.youtube.com/watch?v=$id&feature=share"))
        assertEquals(VideoRef(id), YouTubeUrl.parse("https://music.youtube.com/watch?v=$id"))
    }

    @Test fun shortLinkWithShareTracking() =
        assertEquals(VideoRef(id), YouTubeUrl.parse("https://youtu.be/$id?si=AbCdEfGhIjKl"))

    @Test fun shorts() = assertEquals(VideoRef(id), YouTubeUrl.parse("https://youtube.com/shorts/$id?feature=share"))

    @Test fun liveEmbedAndNoCookie() {
        assertEquals(VideoRef(id), YouTubeUrl.parse("https://www.youtube.com/live/$id"))
        assertEquals(VideoRef(id), YouTubeUrl.parse("https://www.youtube.com/embed/$id"))
        assertEquals(VideoRef(id), YouTubeUrl.parse("https://www.youtube-nocookie.com/embed/$id"))
    }

    @Test fun shareSheetText() = assertEquals(
        VideoRef(id),
        YouTubeUrl.parse("Check this out! https://youtu.be/$id?si=xyz shared from YouTube"),
    )

    @Test fun bareHostWithoutScheme() = assertEquals(VideoRef(id), YouTubeUrl.parse("youtube.com/watch?v=$id"))

    @Test fun bareId() = assertEquals(VideoRef(id), YouTubeUrl.parse("  $id "))

    @Test fun timestamps() {
        assertEquals(VideoRef(id, 90), YouTubeUrl.parse("https://youtu.be/$id?t=90"))
        assertEquals(VideoRef(id, 90), YouTubeUrl.parse("https://www.youtube.com/watch?v=$id&t=1m30s"))
        assertEquals(VideoRef(id, 3723), YouTubeUrl.parse("https://www.youtube.com/watch?v=$id&t=1h2m3s"))
        assertEquals(VideoRef(id, 42), YouTubeUrl.parse("https://www.youtube.com/watch?v=$id#t=42"))
        assertEquals(VideoRef(id, 0), YouTubeUrl.parse("https://www.youtube.com/watch?v=$id&t=bogus"))
    }

    @Test fun playlistLinkPlaysTheVideo() =
        assertEquals(VideoRef(id), YouTubeUrl.parse("https://www.youtube.com/watch?v=$id&list=PL1234567890"))

    @Test fun trailingPunctuationIsIgnored() =
        assertEquals(VideoRef(id), YouTubeUrl.parse("(see https://youtu.be/$id)."))

    @Test fun rejectsNonVideoLinks() {
        assertNull(YouTubeUrl.parse(null))
        assertNull(YouTubeUrl.parse(""))
        assertNull(YouTubeUrl.parse("https://www.youtube.com/@somechannel"))
        assertNull(YouTubeUrl.parse("https://www.youtube.com/playlist?list=PL1234567890"))
        assertNull(YouTubeUrl.parse("https://example.com/watch?v=$id"))
        assertNull(YouTubeUrl.parse("https://youtu.be/short"))
        assertNull(YouTubeUrl.parse("hello world"))
        assertNull(YouTubeUrl.parse("https://evil-youtube.com/watch?v=$id"))
    }
}
