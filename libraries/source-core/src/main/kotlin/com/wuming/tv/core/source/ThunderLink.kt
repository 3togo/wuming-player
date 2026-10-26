package com.wuming.tv.core.source

import java.util.Locale

/** Protocol spelling accepted by the Android Thunder adapter and its native helper. */
object ThunderLink {
    @JvmStatic
    fun matches(raw: String): Boolean = when (raw.substringBefore(':', "").lowercase(Locale.ROOT)) {
        "ftp" -> raw.startsWith("ftp://", ignoreCase = true)
        "ed2k" -> raw.startsWith("ed2k://", ignoreCase = true)
        "magnet" -> raw.startsWith("magnet:", ignoreCase = true)
        else -> false
    }

    @JvmStatic
    fun nativeUrl(raw: String): String {
        if (!matches(raw)) return raw
        val url = if (raw.startsWith("ftp://", ignoreCase = true)) raw.substringBefore('|') else raw
        return normalizeScheme(url)
    }

    @JvmStatic
    fun normalizeScheme(url: String): String {
        if (!matches(url)) return url
        val separator = url.indexOf(':')
        return url.substring(0, separator).lowercase(Locale.ROOT) + url.substring(separator)
    }

    /** Only a leading download scheme or a torrent file is eligible for episode expansion. */
    @JvmStatic
    fun parseCandidate(raw: String): Boolean {
        val scheme = raw.substringBefore(':', "").lowercase(Locale.ROOT)
        return when (scheme) {
            "magnet" -> raw.startsWith("magnet:?", ignoreCase = true)
            "thunder" -> raw.startsWith("thunder://", ignoreCase = true)
            "ed2k" -> raw.startsWith("ed2k://", ignoreCase = true)
            else -> isTorrent(raw)
        }
    }

    @JvmStatic
    fun isTorrent(raw: String): Boolean {
        val path = raw.substringBefore(';').substringBefore('?').substringBefore('#')
        return path.endsWith(".torrent", ignoreCase = true)
    }

    @JvmStatic
    fun parserUrl(raw: String): String {
        val scheme = raw.substringBefore(':', "").lowercase(Locale.ROOT)
        if (scheme != "thunder" && scheme != "magnet" && scheme != "ed2k") return raw
        return scheme + raw.substring(raw.indexOf(':'))
    }
}
