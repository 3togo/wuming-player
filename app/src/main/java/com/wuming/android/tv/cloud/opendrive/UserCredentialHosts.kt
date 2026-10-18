package com.wuming.android.tv.cloud.opendrive

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Pure host-matching helpers for [UserCredentialStore.findByUrl].
 *
 * Extracted from [UserCredentialStore] so the matching rules (exact host,
 * subdomain, scheme/port-agnostic) can be unit-tested on the JVM without the Android
 * keystore / `Init.context()` the full store depends on.
 */
object UserCredentialHosts {
    /** Host of [url], lower-cased by okhttp, or null when it cannot be parsed. */
    @JvmStatic
    fun hostOf(url: String?): String? {
        var host = parseHost(url)
        if (host != null) return host
        // Users may enter a bare host (e.g. "lmteam.com") without a scheme; okhttp
        // requires a scheme to parse, so retry with a default one so stored base URLs
        // still match their requests.
        if (url != null && url.isNotEmpty()) host = parseHost("https://$url")
        return host
    }

    private fun parseHost(url: String?): String? =
        try {
            url?.toHttpUrlOrNull()?.host
        } catch (_: RuntimeException) {
            null
        }

    /**
     * True when [requestUrl]'s host equals [baseUrl]'s host or is a subdomain of it.
     * Scheme and port are ignored. Blank or invalid inputs never match.
     */
    @JvmStatic
    fun matches(baseUrl: String?, requestUrl: String?): Boolean {
        val baseHost = hostOf(baseUrl)
        val requestHost = hostOf(requestUrl)
        if (baseHost.isNullOrEmpty() || requestHost.isNullOrEmpty()) return false
        return requestHost == baseHost || requestHost.endsWith(".$baseHost")
    }
}
