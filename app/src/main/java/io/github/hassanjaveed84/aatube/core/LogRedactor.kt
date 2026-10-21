package io.github.hassanjaveed84.aatube.core

/**
 * Keeps URLs and tokens out of logs. A URL keeps its host and the shape of
 * its path; query values (si=, pp=, session ids) and video ids are masked
 * down to a short prefix that is still useful for matching log lines.
 */
object LogRedactor {

    private val URL = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)

    fun redact(text: String): String = URL.replace(text) { redactUrl(it.value) }

    fun redactUrl(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return mask(url)
        val rest = url.substring(schemeEnd + 3)
        val host = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        val path = rest.removePrefix(host).substringBefore('?').substringBefore('#')
        val maskedPath = path.split('/').joinToString("/") { segment ->
            if (segment.length > 6) mask(segment) else segment
        }
        val hasQuery = '?' in rest || '#' in rest
        return url.substring(0, schemeEnd + 3) + host + maskedPath + if (hasQuery) "?…" else ""
    }

    fun videoId(id: String): String = mask(id)

    private fun mask(value: String): String =
        if (value.length <= 3) "***" else value.take(3) + "…"
}
