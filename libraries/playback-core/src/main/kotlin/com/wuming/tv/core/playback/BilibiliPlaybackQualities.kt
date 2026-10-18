package com.wuming.tv.core.playback

import com.google.gson.JsonParser
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Resolve only qualities backed by playable DASH tracks, never advertised entitlement labels. */
object BilibiliPlaybackQualities {
    data class Source(val url: String, val aid: String, val cid: String) {
        fun apiUrl(): String = "https://api.bilibili.com/x/player/playurl?avid=$aid&cid=$cid&qn=127&fnval=4048&fourk=1"
        fun qualityUrl(quality: Int): String {
            val uri = URI(url)
            val query = uri.rawQuery.split('&').filterNot { it.substringBefore('=') == "qn" }
            return url.substringBefore('?') + "?" + (query + "qn=$quality").joinToString("&")
        }
    }
    data class Quality(val id: Int, val label: String, val url: String)

    @JvmStatic fun source(url: String): Source? = runCatching {
        val uri = URI(url)
        if (uri.scheme != "http" || uri.host !in listOf("127.0.0.1", "localhost", "::1", "[::1]") ||
            uri.path != "/proxy" || uri.userInfo != null || uri.fragment != null) return null
        val pairs = uri.rawQuery.orEmpty().split('&').map {
            URLDecoder.decode(it.substringBefore('='), StandardCharsets.UTF_8.name()) to
                URLDecoder.decode(it.substringAfter('=', ""), StandardCharsets.UTF_8.name())
        }
        if (pairs.map { it.first }.distinct().size != pairs.size) return null
        val query = pairs.toMap()
        val aid = query["aid"].orEmpty(); val cid = query["cid"].orEmpty()
        if (query["do"] != "bili" || query["type"] != "mpd" ||
            !aid.matches(Regex("[0-9]{1,20}")) || !cid.matches(Regex("[0-9]{1,20}"))) return null
        Source(url, aid, cid)
    }.getOrNull()

    @JvmStatic fun parse(source: Source, response: String): List<Quality> {
        val root = JsonParser.parseString(response).asJsonObject
        require(root.get("code")?.asInt == 0) { "Bilibili quality request failed" }
        val data = root.getAsJsonObject("data") ?: return emptyList()
        // REGRESSION: accept_quality advertised 720p even when anonymous dash.video stopped
        // at 480p. Only actual returned tracks establish an available quality; labels do not.
        // Covered by authenticTracksControlQualityMenuNotAdvertisedEntitlements and
        // anonymousResponseDoesNotInvent720p in BilibiliPlaybackQualitiesTest.
        val video = data.getAsJsonObject("dash")?.getAsJsonArray("video") ?: return emptyList()
        val heights = linkedMapOf<Int, Int>()
        for (entry in video) {
            val track = entry.asJsonObject
            val id = track.get("id")?.asInt ?: continue
            val height = track.get("height")?.asInt ?: continue
            val base = (track.get("baseUrl") ?: track.get("base_url"))?.asString.orEmpty()
            if (id > 0 && height > 0 && base.isNotBlank()) heights[id] = height
        }
        val labels = data.getAsJsonArray("support_formats")?.associate { entry ->
            val format = entry.asJsonObject
            format.get("quality").asInt to (format.get("new_description") ?: format.get("display_desc"))?.asString
        }.orEmpty()
        return heights.entries.sortedByDescending { it.key }.map { (id, height) ->
            Quality(id, labels[id]?.takeIf { it.isNotBlank() } ?: "${height}P", source.qualityUrl(id))
        }
    }
}
