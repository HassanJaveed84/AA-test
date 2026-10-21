package io.github.hassanjaveed84.aatube.core

/**
 * What YouTube's public oEmbed endpoint says about a video. oEmbed answers
 * 401 for videos whose owner disabled embedding, which separates that case
 * from other reasons the embedded player can refuse a video (sign-in or
 * bot checks, age or region gates), all of which surface as error 150/152.
 */
enum class EmbedStatus(val explanation: String) {
    EMBEDDABLE("oEmbed 200: the video allows embedding, so YouTube refused this player for another reason (sign-in/bot check, age or region)"),
    NOT_EMBEDDABLE("oEmbed 401/403: the owner disabled embedding"),
    NOT_FOUND("oEmbed 400/404: video private, removed or invalid"),
    UNKNOWN("oEmbed check failed or returned an unexpected status"),
    ;

    companion object {
        fun fromHttpCode(code: Int?): EmbedStatus = when (code) {
            200 -> EMBEDDABLE
            401, 403 -> NOT_EMBEDDABLE
            400, 404 -> NOT_FOUND
            else -> UNKNOWN
        }

        fun oEmbedUrl(videoId: String): String {
            require(Regex("^[A-Za-z0-9_-]{11}$").matches(videoId)) { "invalid video id" }
            return "https://www.youtube.com/oembed?format=json&url=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3D$videoId"
        }
    }
}
