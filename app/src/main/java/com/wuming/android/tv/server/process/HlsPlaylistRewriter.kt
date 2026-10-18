package com.wuming.android.tv.server.process

import com.wuming.android.tv.utils.AppLog
import com.wuming.tv.core.playback.hls.HighestQualityVariantTransform
import java.nio.charset.StandardCharsets
import java.util.Locale
import okhttp3.HttpUrl
import okhttp3.ResponseBody

/** Maps a resolved upstream URL to its relay/child URL. */
internal fun interface UrlMapper {
    fun map(url: String): String
}

/** HLS playlist detection, rewriting, and resolution-preference helpers for cast relay. */
internal class HlsPlaylistRewriter private constructor() {

    internal companion object {

        @JvmStatic
        fun isHls(url: String, mime: String, body: ResponseBody): Boolean {
            val lowerUrl = url.lowercase(Locale.US)
            val lowerMime = mime.lowercase(Locale.US)
            if (lowerUrl.contains(".m3u8") || lowerMime.contains("mpegurl") || lowerMime.contains("m3u8")) return true
            try {
                val preview = body.source().peek()
                if (preview.request(7) && isHlsPrefix(preview.readUtf8(7))) return true
            } catch (e: Exception) {
                AppLog.w("CastMediaRelay", "Unable to inspect cast media response: ${e.message}")
            }
            return false
        }

        @JvmStatic
        fun isHlsPrefix(prefix: String?): Boolean = prefix != null && prefix.startsWith("#EXTM3U")

        @JvmStatic
        fun canRewritePlaylist(upstreamStatus: Int): Boolean = upstreamStatus in 200..299

        @JvmStatic
        fun relayContentLength(upstreamLength: Long): Long = if (upstreamLength < 0L) -1L else upstreamLength

        @JvmStatic
        fun rewritePlaylist(playlist: String?, base: HttpUrl, mapper: UrlMapper): String {
            return com.wuming.tv.core.playback.HlsReferenceRewriter.rewrite(playlist,
                { value -> base.resolve(value)?.toString() }, { url, _ -> mapper.map(url) })
        }

        @JvmStatic
        fun preferHighestResolution(playlist: String?): String? {
            if (playlist == null) return null
            return HighestQualityVariantTransform().apply(playlist, null).content
        }

    }
}
