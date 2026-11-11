package com.wuming.tv.core.playback

import java.net.URI
import java.net.URLDecoder

/** Keeps the playlist transport location separate from the provider's rule scope. */
object HlsPlaylistLocation {
    @JvmStatic
    fun shouldPurify(url: String?): Boolean {
        if (url == null || !url.contains(".m3u8")) return false
        return try {
            val uri = URI.create(url)
            !isLoopback(uri) || isProviderPlaylist(uri) ||
                (uri.path != null && uri.path.endsWith(".m3u8"))
        } catch (ignored: IllegalArgumentException) {
            false
        }
    }

    @JvmStatic
    fun baseUrl(url: String): String = URI.create(url).resolve(".").toString()

    @JvmStatic
    fun ruleBaseUrl(url: String): String {
        val uri = URI.create(url)
        if (isProviderPlaylist(uri)) {
            try {
                val origin = URI.create(parameter(uri, "url"))
                if (("https".equals(origin.scheme, ignoreCase = true) || "http".equals(origin.scheme, ignoreCase = true))
                    && origin.host != null && !isLoopback(origin)) {
                    return baseUrl(origin.toString())
                }
            } catch (ignored: IllegalArgumentException) {
            }
        }
        return baseUrl(url)
    }

    @JvmStatic
    fun ruleBaseUrl(url: String, initialUrl: String): String {
        if (isProviderPlaylist(URI.create(url))) return ruleBaseUrl(url)
        if (isLoopback(URI.create(url)) && isProviderPlaylist(URI.create(initialUrl))) {
            return ruleBaseUrl(initialUrl)
        }
        return ruleBaseUrl(url)
    }

    /** Both Android's fixed endpoint and desktop's per-provider capability endpoint. */
    @JvmStatic
    fun isProviderPlaylist(url: String?): Boolean = runCatching {
        url != null && isProviderPlaylist(URI.create(url))
    }.getOrDefault(false)

    private fun isProviderPlaylist(uri: URI): Boolean =
        isLoopback(uri) && uri.path.orEmpty().endsWith("/proxy") &&
            "m3u8".equals(parameter(uri, "do"), ignoreCase = true)

    private fun isLoopback(uri: URI): Boolean {
        val host = uri.host
        return "127.0.0.1" == host || "localhost".equals(host, ignoreCase = true) ||
            "[::1]" == host || "::1" == host
    }

    private fun parameter(uri: URI, name: String): String {
        val query = uri.rawQuery ?: return ""
        for (part in query.split("&")) {
            val equals = part.indexOf('=')
            if (equals >= 0 && name.equals(part.substring(0, equals), ignoreCase = true)) {
                return URLDecoder.decode(part.substring(equals + 1), "UTF-8")
            }
        }
        return ""
    }
}
