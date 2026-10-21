package io.github.hassanjaveed84.aatube.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LogRedactorTest {

    @Test fun masksQueryAndLongPathSegments() =
        assertEquals("https://youtu.be/dQw…?…", LogRedactor.redactUrl("https://youtu.be/dQw4w9WgXcQ?si=SeCrEt"))

    @Test fun keepsShortPathSegments() =
        assertEquals("https://www.youtube.com/tv", LogRedactor.redactUrl("https://www.youtube.com/tv"))

    @Test fun redactsUrlsInsideText() {
        val text = LogRedactor.redact("loading https://www.youtube.com/watch?v=dQw4w9WgXcQ&pp=token now")
        assertEquals("loading https://www.youtube.com/watch?… now", text)
        assertFalse(text.contains("token"))
    }

    @Test fun videoIdKeepsPrefixOnly() = assertEquals("dQw…", LogRedactor.videoId("dQw4w9WgXcQ"))
}
