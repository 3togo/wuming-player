package com.wuming.tv.core.playback

import java.net.URI
import okhttp3.Request

object DashRelayPolicy {
    /** Rewritten manifests describe a new representation, so upstream byte ranges do not apply. */
    @JvmStatic
    fun upstreamRequest(root: String, target: String, original: Map<String, String>,
                        method: String, range: String?, accept: String?): Request {
        val manifest = isManifest(target)
        return Request.Builder().url(target).apply {
            if (method == "HEAD" && !manifest) head()
            for ((key, value) in headers(root, target, original)) {
                if (manifest && (key.equals("Range", true) || key.equals("If-Range", true))) continue
                try { header(key, value) } catch (_: IllegalArgumentException) { /* Invalid provider header. */ }
            }
            if (!manifest && !range.isNullOrEmpty()) header("Range", range)
            if (!accept.isNullOrEmpty()) header("Accept", accept)
        }.build()
    }

    private fun query(url: String): Map<String, String> = try {
        URI(url).rawQuery.orEmpty().split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
    } catch (_: Exception) { emptyMap() }
    @JvmStatic fun isManifest(url: String): Boolean = try {
        URI(url).path.orEmpty().endsWith(".mpd", true) || query(url)["type"] == "mpd"
    } catch (_: Exception) { false }
    @JvmStatic fun isBilibiliManifest(url: String): Boolean = query(url)["do"] == "bili" && isManifest(url)
    @JvmStatic fun headers(root: String, target: String, original: Map<String, String>): Map<String, String> {
        val host = try { URI(target).host?.lowercase().orEmpty() } catch (_: Exception) { "" }
        if (query(root)["do"] != "bili" || !isManifest(root) ||
            !(host == "bilivideo.com" || host.endsWith(".bilivideo.com") || host == "bilivideo.cn" || host.endsWith(".bilivideo.cn"))) return original
        // REGRESSION: an actual AAC CDN request returned 403 without browser context and
        // 206 with this Referer/User-Agent pair. Keep this scoped to Bilibili media hosts.
        // Removing Cookie here is intentional: account auth resolves quality on api.bilibili.com;
        // signed CDN URLs need media headers, not account secrets. Do not forward login
        // credentials to the TV/CDN to fix a missing quality-menu entry.
        val headers = original.filterKeys { !it.equals("Cookie", true) && !it.equals("Authorization", true) }.toMutableMap()
        if (headers.keys.none { it.equals("Referer", true) }) headers["Referer"] = "https://www.bilibili.com/"
        if (headers.keys.none { it.equals("User-Agent", true) }) headers["User-Agent"] = "Mozilla/5.0"
        return headers
    }
}
