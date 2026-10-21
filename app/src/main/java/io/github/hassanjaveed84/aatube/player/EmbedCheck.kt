package io.github.hassanjaveed84.aatube.player

import io.github.hassanjaveed84.aatube.core.EmbedStatus
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Asks YouTube's oEmbed endpoint whether a video allows embedding (diagnostic only). */
object EmbedCheck {

    private val io = Executors.newSingleThreadExecutor()

    fun check(videoId: String, onResult: (EmbedStatus, Int?) -> Unit) {
        io.execute {
            val code = runCatching {
                val connection = URL(EmbedStatus.oEmbedUrl(videoId)).openConnection() as HttpURLConnection
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.instanceFollowRedirects = true
                try {
                    connection.responseCode
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()
            onResult(EmbedStatus.fromHttpCode(code), code)
        }
    }
}
