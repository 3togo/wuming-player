package com.wuming.tv.core.playback

import java.net.URI
import java.util.Locale

/** Recognizes a media resource from its path, never from a nested URL in a web page's query. */
object MediaUrlClassifier {
    private val progressive = setOf(
        "mp4", "m4v", "mkv", "webm", "mov", "avi", "flv", "ts",
        "mp3", "m4a", "m4b", "aac", "flac", "ogg", "oga", "opus", "wav",
    )
    private val manifests = setOf("m3u8", "mpd")

    @JvmStatic
    fun isMediaUrl(value: String?): Boolean {
        val uri = parse(value) ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme == "rtmp" || scheme == "rtmps") return !uri.host.isNullOrBlank()
        if (!isHttp(uri)) return false
        val path = uri.path.orEmpty().lowercase(Locale.ROOT)
        val extension = path.substringAfterLast('/').substringAfterLast('.', "")
        if (extension == "html" || extension == "htm") return false
        return extension in progressive || extension in manifests || path.contains("/video/tos")
    }

    @JvmStatic
    fun isProgressiveUrl(value: String?): Boolean {
        val uri = parse(value) ?: return false
        return isHttp(uri) && uri.path.orEmpty().substringAfterLast('/')
            .substringAfterLast('.', "").lowercase(Locale.ROOT) in progressive
    }

    private fun parse(value: String?): URI? = value?.trim()?.takeIf(String::isNotEmpty)?.let {
        runCatching { URI(it) }.getOrNull()
    }

    private fun isHttp(uri: URI): Boolean = !uri.host.isNullOrBlank() &&
        (uri.scheme.equals("http", true) || uri.scheme.equals("https", true))
}
