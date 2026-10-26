package com.wuming.tv.core.source

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/** URL decisions shared by hosts; the JianPian native engine remains Android-only. */
object JianPianLink {
    @JvmStatic
    fun matches(raw: String): Boolean {
        val url = raw.substringBefore('|')
        val scheme = url.substringBefore(':', "").lowercase(Locale.ROOT)
        if (scheme == "tvbox-xg" || scheme == "jianpian") return true
        if (scheme != "ftp" || !url.startsWith("ftp://", ignoreCase = true)) return false
        val authority = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        val host = authority.substringAfterLast('@').substringBefore(':').lowercase(Locale.ROOT)
        val labels = host.split('.')
        return labels.windowed(2).any { it[0] == "gbl" && it[1] == "114s" }
    }

    /** A literal pipe separates player metadata; an encoded pipe may belong to the filename. */
    @JvmStatic
    fun nativePath(raw: String): String {
        val withoutMetadata = raw.substringBefore('|').replace(ENCODED_METADATA, "")
        // URLDecoder implements form encoding, where '+' means space. URLs use literal '+'.
        val decoded = URLDecoder.decode(withoutMetadata.replace("+", "%2B"), StandardCharsets.UTF_8.name())
        val unwrapped = when {
            decoded.startsWith("jianpian://pathtype=url&path=", true) ->
                decoded.substring("jianpian://pathtype=url&path=".length)
            decoded.startsWith("tvbox-xg://", true) -> decoded.substring("tvbox-xg://".length)
            decoded.startsWith("tvbox-xg:", true) -> decoded.substring("tvbox-xg:".length)
            else -> decoded
        }
        val nativeUrl = when {
            unwrapped.startsWith("xgplay://", true) -> "ftp://" + unwrapped.substring("xgplay://".length)
            unwrapped.startsWith("xg://", true) -> "ftp://" + unwrapped.substring("xg://".length)
            else -> unwrapped
        }
        return ThunderLink.normalizeScheme(nativeUrl)
    }

    private val ENCODED_METADATA = Regex(
        "%7c(?=(?:user-agent|referer|cookie|authorization|origin|range|accept)(?:=|%3d))[^|]*$",
        RegexOption.IGNORE_CASE,
    )
}
