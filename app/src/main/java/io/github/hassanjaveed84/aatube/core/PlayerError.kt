package io.github.hassanjaveed84.aatube.core

/** Player failures, each with a message that says what to do next. */
enum class PlayerError(val userMessage: String) {
    INVALID_VIDEO("That link is not a playable YouTube video id."),
    HTML5_ERROR("The web player failed to decode this video. Try again or use YouTube TV mode."),
    NOT_FOUND("Video not found, private or removed."),
    EMBED_BLOCKED(
        "YouTube refused to play this video in an embedded player (owner setting, region, " +
            "age gate or a sign-in check). Try YouTube TV mode instead.",
    ),
    MISSING_REFERRER("YouTube rejected the player configuration (error 153). This is an app bug; please report it."),
    NETWORK("No connection to YouTube. Check the phone's mobile data or Wi-Fi."),
    UNKNOWN("The player stopped with an unknown error."),
    ;

    companion object {
        /** Maps the IFrame Player API onError codes. */
        fun fromIframeCode(code: Int): PlayerError = when (code) {
            2 -> INVALID_VIDEO
            5 -> HTML5_ERROR
            100 -> NOT_FOUND
            101, 150, 152 -> EMBED_BLOCKED
            153 -> MISSING_REFERRER
            else -> UNKNOWN
        }
    }
}
