package com.wuming.tv.core.cloud

import java.net.URI
import java.util.Locale

/**
 * Shape rules for the short opaque strings cloud providers hand back as credentials. The
 * character set is the URL unreserved class, which is what provider cookies, bearer tokens and
 * share passcodes are restricted to; only the length profile differs per use.
 *
 * Remote membership is still verified by the adapters, exactly as in [SavedCopyPolicy].
 */
object CredentialShapePolicy {

    private val OPAQUE = Regex("[A-Za-z0-9._~-]+")
    private val PASSCODE = Regex("[A-Za-z0-9]+")

    /** Broad login credential: a cookie or passcode that will be sent to a provider API. */
    @JvmStatic
    fun isLoginValue(value: String?): Boolean = matches(value, 8, 2048)

    /** Opaque bearer token, long enough that a truncated capture cannot pass. */
    @JvmStatic
    fun isOpaqueToken(value: String?): Boolean = matches(value, 40, 8192)

    @JvmStatic
    fun matches(value: String?, minLength: Int, maxLength: Int): Boolean {
        if (value == null || value.length < minLength || value.length > maxLength) return false
        return OPAQUE.matches(value)
    }

    /** Share passcode profile shared by every drive that carries one. */
    @JvmStatic
    fun isSharePasscode(value: String?): Boolean {
        return value != null && value.length in 1..16 && PASSCODE.matches(value)
    }
}

/**
 * The bare-https origin guard used before any first-party cloud credential is captured or a
 * share link is trusted. Rejects anything a look-alike page could present: another scheme, an
 * explicit port, embedded userinfo or a missing host.
 */
object FirstPartyOriginPolicy {

    /**
     * Parsed bare-https URL, or null when the value cannot be a first-party origin.
     *
     * @param maxLength caps the raw input before parsing, so an oversized blob never reaches URI.
     * @param trim whether surrounding whitespace is tolerated, which only link parsing does.
     */
    @JvmStatic
    fun httpsUri(value: String?, maxLength: Int, trim: Boolean): URI? {
        if (value == null || value.length > maxLength) return null
        return try {
            val uri = URI.create(if (trim) value.trim() else value)
            if (!"https".equals(uri.scheme, ignoreCase = true) || uri.host == null
                || uri.userInfo != null || uri.port != -1
            ) {
                null
            } else {
                uri
            }
        } catch (ignored: Exception) {
            null
        }
    }

    /** Host of a bare https URL, lowercased, or null when the value is not one. */
    @JvmStatic
    fun httpsHost(value: String?): String? =
        httpsUri(value, Int.MAX_VALUE, false)?.host?.lowercase(Locale.ROOT)

    /** Exact host match, or a subdomain of [domain] when [allowSubdomains] is set. */
    @JvmStatic
    fun isWithinDomain(host: String?, domain: String, allowSubdomains: Boolean): Boolean {
        if (host == null) return false
        val expected = domain.lowercase(Locale.ROOT)
        return host == expected || (allowSubdomains && host.endsWith(".$expected"))
    }
}
