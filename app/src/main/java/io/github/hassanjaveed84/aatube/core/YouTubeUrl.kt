package io.github.hassanjaveed84.aatube.core

import java.net.URI
import java.net.URLDecoder

/** A single YouTube video to play, optionally from an offset. */
data class VideoRef(val id: String, val startSeconds: Int = 0)

/**
 * Pulls a YouTube video out of whatever the user pasted or shared: a bare
 * link, or share-sheet text such as "Look at this https://youtu.be/xyz?si=…".
 *
 * Supported shapes: youtube.com/watch?v=, m./music./www. hosts, youtu.be/ID,
 * /shorts/ID, /live/ID, /embed/ID, /v/ID and youtube-nocookie.com/embed/ID.
 * Playlists are not supported; a link with both v= and list= plays the video.
 */
object YouTubeUrl {

    private val ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val URL_IN_TEXT = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
    private val BARE_HOST_IN_TEXT = Regex(
        """(?:^|\s)((?:www\.|m\.|music\.)?(?:youtube\.com|youtu\.be|youtube-nocookie\.com)/\S+)""",
        RegexOption.IGNORE_CASE,
    )
    private val PATH_PREFIXES = listOf("shorts", "live", "embed", "v", "e")

    fun parse(text: String?): VideoRef? {
        if (text.isNullOrBlank()) return null
        val candidates = URL_IN_TEXT.findAll(text).map { it.value }.toList() +
            BARE_HOST_IN_TEXT.findAll(text).map { "https://" + it.groupValues[1] }.toList()
        for (candidate in candidates) {
            parseUrl(candidate.trimEnd('.', ',', ')', ']', '"', '\''))?.let { return it }
        }
        val bare = text.trim()
        return if (ID.matches(bare)) VideoRef(bare) else null
    }

    private fun parseUrl(raw: String): VideoRef? {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return null
        val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
        val query = queryParams(uri.rawQuery)
        val id = when {
            host == "youtu.be" -> segments.firstOrNull()
            host == "youtube.com" || host == "music.youtube.com" || host == "youtube-nocookie.com" -> when {
                segments.firstOrNull() == "watch" -> query["v"]
                segments.size >= 2 && segments[0] in PATH_PREFIXES -> segments[1]
                else -> null
            }
            else -> null
        } ?: return null
        if (!ID.matches(id)) return null
        val start = parseTimestamp(query["t"] ?: query["start"] ?: fragmentTime(uri.rawFragment))
        return VideoRef(id, start)
    }

    private fun fragmentTime(fragment: String?): String? =
        fragment?.takeIf { it.startsWith("t=") }?.removePrefix("t=")

    private fun queryParams(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()
        return rawQuery.split('&').mapNotNull { pair ->
            val parts = pair.split('=', limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val key = URLDecoder.decode(parts[0], "UTF-8")
            val value = URLDecoder.decode(parts[1], "UTF-8")
            key to value
        }.toMap()
    }

    /** "90", "90s", "1m30s", "1h2m3s" -> seconds; anything else -> 0. */
    fun parseTimestamp(value: String?): Int {
        if (value.isNullOrBlank()) return 0
        value.toIntOrNull()?.let { return it.coerceAtLeast(0) }
        val match = Regex("""^(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?$""").matchEntire(value) ?: return 0
        val (h, m, s) = match.destructured
        return (h.toIntOrNull() ?: 0) * 3600 + (m.toIntOrNull() ?: 0) * 60 + (s.toIntOrNull() ?: 0)
    }
}
