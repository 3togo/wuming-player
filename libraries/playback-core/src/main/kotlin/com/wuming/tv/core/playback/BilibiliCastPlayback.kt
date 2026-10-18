package com.wuming.tv.core.playback

import com.google.gson.JsonParser
import java.net.URI

/** Prefer a real combined MP4 for DLNA; API quality, not the requested label, describes the result. */
object BilibiliCastPlayback {
    val headers: Map<String, String> = mapOf("User-Agent" to "Mozilla/5.0", "Referer" to "https://www.bilibili.com/")
    data class Source(val aid: String, val cid: String, val requestedQuality: Int) {
        fun apiUrl(): String = "https://api.bilibili.com/x/player/playurl?avid=$aid&cid=$cid&qn=$requestedQuality&fnval=1&platform=html5&high_quality=1"
    }
    data class Stream(val url: String, val quality: Int, val label: String)
    fun interface Transport { fun get(url: String, headers: Map<String, String>): String }

    @JvmStatic fun source(url: String, localServer: String? = null): Source? = runCatching {
        val uri = URI(url)
        if (uri.userInfo != null || uri.fragment != null) return null
        val local = localServer?.let(::URI)
        val normalized = if (local != null && uri.scheme == "http" && local.scheme == "http" &&
            uri.host == local.host && uri.port == local.port) {
            "http://127.0.0.1:${uri.port}${uri.rawPath}?${uri.rawQuery}"
        } else url
        val source = BilibiliPlaybackQualities.source(normalized) ?: return null
        val quality = URI(normalized).rawQuery.split('&').firstOrNull { it.startsWith("qn=") }
            ?.substringAfter('=')?.toIntOrNull()?.takeIf { it in 1..1000 } ?: return null
        Source(source.aid, source.cid, quality)
    }.getOrNull()

    @JvmStatic fun parse(source: Source, response: String): Stream? = runCatching {
        require(response.length <= 1_000_000)
        val root = JsonParser.parseString(response).asJsonObject
        if (root.get("code")?.asInt != 0) return null
        val data = root.getAsJsonObject("data") ?: return null
        val quality = data.get("quality")?.asInt ?: return null
        if (quality !in 1..source.requestedQuality) return null
        val format = data.get("format")?.asString.orEmpty()
        if (!format.matches(Regex("mp4[0-9]*"))) return null
        // Multi-part FLV/durl responses cannot be advertised as one progressive MP4.
        val parts = data.getAsJsonArray("durl") ?: return null
        if (parts.size() != 1) return null
        val url = parts[0].asJsonObject.get("url")?.asString ?: return null
        val uri = URI(url)
        val host = uri.host?.lowercase().orEmpty()
        if (uri.scheme !in listOf("http", "https") || uri.userInfo != null || uri.fragment != null ||
            !(host == "bilivideo.com" || host.endsWith(".bilivideo.com") ||
                host == "bilivideo.cn" || host.endsWith(".bilivideo.cn"))) return null
        val index = data.getAsJsonArray("accept_quality")?.indexOfFirst { it.asInt == quality } ?: -1
        val descriptions = data.getAsJsonArray("accept_description")
        val label = if (index >= 0 && descriptions != null && index < descriptions.size())
            descriptions[index].asString.replace('\r', ' ').replace('\n', ' ').take(40)
        else format.removePrefix("mp4").takeIf { it.isNotEmpty() }?.let { "${it}P" } ?: "MP4"
        Stream(url, quality, label)
    }.getOrNull()

    @JvmStatic fun resolve(url: String, localServer: String?, transport: Transport): Stream? {
        val source = source(url, localServer) ?: return null
        return parse(source, transport.get(source.apiUrl(), headers))
    }
}
