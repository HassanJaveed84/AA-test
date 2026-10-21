package io.github.hassanjaveed84.aatube.core

import org.junit.Assert.assertEquals
import org.junit.Test

class EmbedStatusTest {

    @Test fun mapsOEmbedCodes() {
        assertEquals(EmbedStatus.EMBEDDABLE, EmbedStatus.fromHttpCode(200))
        assertEquals(EmbedStatus.NOT_EMBEDDABLE, EmbedStatus.fromHttpCode(401))
        assertEquals(EmbedStatus.NOT_EMBEDDABLE, EmbedStatus.fromHttpCode(403))
        assertEquals(EmbedStatus.NOT_FOUND, EmbedStatus.fromHttpCode(404))
        assertEquals(EmbedStatus.UNKNOWN, EmbedStatus.fromHttpCode(null))
        assertEquals(EmbedStatus.UNKNOWN, EmbedStatus.fromHttpCode(500))
    }

    @Test fun buildsOEmbedUrl() = assertEquals(
        "https://www.youtube.com/oembed?format=json&url=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3DdQw4w9WgXcQ",
        EmbedStatus.oEmbedUrl("dQw4w9WgXcQ"),
    )

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBadIds() {
        EmbedStatus.oEmbedUrl("bad&id")
    }
}
