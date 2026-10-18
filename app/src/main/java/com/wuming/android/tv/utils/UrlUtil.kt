package com.wuming.android.tv.utils

import android.net.Uri
import com.wuming.android.tv.server.Server
import com.github.catvod.utils.UriUtil
import com.google.common.net.HttpHeaders
import java.net.IDN
import java.net.URI
import java.util.Locale

object UrlUtil {
    @JvmStatic
    fun uri(url: String?): Uri {
        if (url.isNullOrEmpty()) return Uri.EMPTY
        return Uri.parse(url.trim().replace("\\", ""))
    }

    @JvmStatic
    fun scheme(url: String?): String = if (url == null) "" else scheme(Uri.parse(url))

    @JvmStatic
    fun scheme(uri: Uri): String {
        val scheme = uri.scheme
        return scheme?.lowercase(Locale.ROOT)?.trim() ?: ""
    }

    @JvmStatic
    fun host(url: String?): String = if (url == null) "" else host(url, Uri.parse(url))

    @JvmStatic
    fun host(uri: Uri): String = host(uri.toString(), uri)

    private fun host(url: String, uri: Uri): String {
        var host = uri.host
        if (host == null) {
            // Uri.parse() returns null for Chinese/IDN hostnames
            // Use java.net.URI which supports non-ASCII hostnames
            try {
                host = URI(url).host
            } catch (ignored: Exception) {
            }
        }
        if (host == null) return ""
        try {
            host = IDN.toASCII(host)
        } catch (ignored: Exception) {
        }
        return host.lowercase(Locale.ROOT).trim()
    }

    @JvmStatic
    fun path(url: String?): String = if (url == null) "" else path(Uri.parse(url))

    @JvmStatic
    fun path(uri: Uri): String {
        val path = uri.lastPathSegment
        return path?.trim() ?: ""
    }

    @JvmStatic
    fun resolve(baseUri: String, referenceUri: String): String =
        UriUtil.resolve(baseUri, referenceUri)

    @JvmStatic
    fun convert(url: String): String {
        if (url.startsWith("package://")) return Server.get().getAddress("/packages/config/" + url.removePrefix("package://"))
        val scheme = scheme(url)
        var path: String? = null
        if (scheme == "assets") path = "/"
        else if (scheme == "file") path = "/file/"
        else if (scheme == "proxy") path = "/proxy?"
        if (path != null) return url.replace("$scheme://", Server.get().getAddress(path))
        // Do NOT convert Chinese domains to punycode here.
        // OkHttp + custom OkDns will handle IDN properly during DNS resolution,
        // while preserving the original hostname in the HTTP Host header.
        return url
    }

    @JvmStatic
    fun getName(url: String?): String {
        if (url.isNullOrEmpty()) return ""
        val uri = Uri.parse(url)
        val path = path(uri)
        val host = host(url)
        return if (path.isNotEmpty()) path else if (host.isNotEmpty()) host else url
    }

    @JvmStatic
    fun fixHeader(key: String): String {
        if (HttpHeaders.USER_AGENT.equals(key, ignoreCase = true)) return HttpHeaders.USER_AGENT
        if (HttpHeaders.REFERER.equals(key, ignoreCase = true)) return HttpHeaders.REFERER
        if (HttpHeaders.COOKIE.equals(key, ignoreCase = true)) return HttpHeaders.COOKIE
        return key
    }

    @JvmStatic
    fun isHttp(url: String?): Boolean {
        if (url == null) return false
        val lower = url.trim().lowercase(Locale.ROOT)
        return lower.startsWith("http://") || lower.startsWith("https://")
    }

    @JvmStatic
    fun isHttpScheme(scheme: String?): Boolean =
        "http".equals(scheme, ignoreCase = true) || "https".equals(scheme, ignoreCase = true)
}
