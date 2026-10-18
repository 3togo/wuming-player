package com.wuming.tv.core.base

/**
 * Byte-range arithmetic shared by every HTTP hop: relays, casting proxies and cloud readers.
 *
 * Only the wire forms live here — clamping a range to a body size stays with the caller that knows
 * that size.
 */
object ByteRanges {

    private val REQUEST = Regex("bytes=(\\d*)-(\\d*)", RegexOption.IGNORE_CASE)
    private val SUFFIX_REQUEST = Regex("bytes=-(\\d+)", RegexOption.IGNORE_CASE)
    private val RESPONSE = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE)

    /** `bytes=first-last`; [endInclusive] is null for an open-ended `bytes=first-`. */
    data class Request(val start: Long, val endInclusive: Long?)

    /** `bytes first-last/total`; [total] is null when the server answered with `*`. */
    data class Response(val start: Long, val endInclusive: Long, val total: Long?)

    @JvmStatic
    fun parseRequest(header: String?): Request? {
        val match = header?.trim()?.let { REQUEST.matchEntire(it) } ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].takeIf(String::isNotEmpty)?.toLongOrNull() ?: return Request(start, null)
        if (end < start) return null
        return Request(start, end)
    }

    /** `bytes=-count`: the last [count] bytes of a body, resolved by the caller that knows its size. */
    @JvmStatic
    fun parseSuffixRequest(header: String?): Long? =
        header?.trim()?.let { SUFFIX_REQUEST.matchEntire(it) }?.groupValues?.get(1)?.toLongOrNull()

    @JvmStatic
    fun parseResponse(header: String?): Response? {
        val match = header?.trim()?.let { RESPONSE.matchEntire(it) } ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        if (end < start) return null
        val total = match.groupValues[3].takeUnless { it == "*" }?.toLongOrNull()
        if (total != null && total <= 0) return null
        return Response(start, end, total)
    }

    /** Renders a `Content-Range` value; `*` stands for an unknown total. */
    @JvmStatic
    fun responseHeader(start: Long, endInclusive: Long, total: Long?): String =
        "bytes $start-$endInclusive/${total ?: "*"}"
}
