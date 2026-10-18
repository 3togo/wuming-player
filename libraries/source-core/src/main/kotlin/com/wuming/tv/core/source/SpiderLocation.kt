package com.wuming.tv.core.source

/** How a spider/jar reference names the digest the artifact must satisfy. */
enum class SpiderDigestKind {
    MD5,
    SHA256,
}

/**
 * The digest half of a `spider`/`jar` reference. Hosts sometimes publish the digest as a
 * separate file, so the value is either the digest itself or the URL that serves it.
 */
data class SpiderDigest(val kind: SpiderDigestKind, val value: String) {

    /** Whether the digest has to be fetched before it can be compared. */
    val isRemote: Boolean
        get() = value.startsWith("http://", true) || value.startsWith("https://", true)
}

/** A split artifact reference: what to download, plus the integrity hint that came with it. */
data class SpiderReference(val location: String, val digest: SpiderDigest?)

/**
 * Parses the `;md5;<hash>` / `;sha256;<hash>` suffix TVBox hosts append to `spider` and `jar`
 * values. The suffix is not part of the location: keeping it attached makes Android request a
 * path no server has, and the desktop client percent-encodes the `;` into the URL.
 */
object SpiderLocation {

    private const val MD5_SEPARATOR = ";md5;"
    private const val SHA256_SEPARATOR = ";sha256;"

    /** `null` when there is no reference at all. */
    @JvmStatic
    fun parse(raw: String?): SpiderReference? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        val md5 = value.indexOf(MD5_SEPARATOR)
        val sha256 = value.indexOf(SHA256_SEPARATOR)
        val index = listOf(md5, sha256).filter { it >= 0 }.minOrNull()
            ?: return SpiderReference(value, null)
        val separator = if (index == md5) MD5_SEPARATOR else SHA256_SEPARATOR
        val kind = if (index == md5) SpiderDigestKind.MD5 else SpiderDigestKind.SHA256
        return SpiderReference(
            location = value.substring(0, index).trim(),
            digest = SpiderDigest(kind, cut(value.substring(index + separator.length))),
        )
    }

    /** Just the location, for callers that pin artifacts by other means. */
    @JvmStatic
    fun locationOf(raw: String?): String = parse(raw)?.location.orEmpty()

    /** A further suffix inside the digest tail is host noise, not part of the digest. */
    private fun cut(tail: String): String {
        val inner = listOf(tail.indexOf(MD5_SEPARATOR), tail.indexOf(SHA256_SEPARATOR)).filter { it >= 0 }.minOrNull()
        return (if (inner == null) tail else tail.substring(0, inner)).trim()
    }
}
