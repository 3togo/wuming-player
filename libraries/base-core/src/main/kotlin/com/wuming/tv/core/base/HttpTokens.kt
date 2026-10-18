package com.wuming.tv.core.base

/**
 * RFC 7230 token shapes for HTTP metadata.
 *
 * Header, cookie and directive names are tokens; anything else must never reach a request or a
 * stored header map. The forbidden-name policy is deliberately NOT here: which hop-by-hop headers
 * a given hop strips depends on that hop, so callers keep their own set and combine it with these
 * shape checks.
 */
object HttpTokens {

    /** RFC 7230 `tchar`, capped so an unbounded name can never be used for header smuggling. */
    @JvmField
    val TOKEN = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}")

    @JvmStatic
    fun isToken(value: String?): Boolean = value != null && TOKEN.matches(value)

    /** A header value must be present, bounded and free of control characters. */
    @JvmStatic
    @JvmOverloads
    fun isHeaderValue(value: String?, maxLength: Int = 8192): Boolean =
        !value.isNullOrEmpty() && value.length <= maxLength && value.none(Character::isISOControl)
}
