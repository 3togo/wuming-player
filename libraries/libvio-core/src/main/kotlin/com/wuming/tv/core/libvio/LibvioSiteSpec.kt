package com.wuming.tv.core.libvio

import com.google.gson.JsonParser
import java.net.URI

/** Explicit identities keep unrelated provider classes and source names untouched. */
data class LibvioSiteSpec(val key: String, val name: String, val baseUrl: String = DEFAULT_URL, val categories: List<String> = emptyList()) {
    init { require(isSiteUrl(baseUrl)) { "Libvio site must use a supported HTTPS origin" } }
    companion object {
        const val DEFAULT_URL = "https://www.libvio.pw/"
        @JvmStatic fun matches(api: String) = api in setOf("builtin_libvio", "csp_Libvio", "csp_LibvioGuard")
        @JvmStatic fun from(key: String, name: String, extension: String): LibvioSiteSpec {
            val url = runCatching { JsonParser.parseString(extension).asJsonObject.get("siteUrl")?.asString }
                .getOrNull()?.takeIf(String::isNotBlank) ?: DEFAULT_URL
            val uri = URI(url)
            require(isSiteUrl(url)) { "Invalid Libvio site URL" }
            return LibvioSiteSpec(key, name, "https://${uri.rawAuthority}/")
        }
        fun isSiteUrl(url: String): Boolean = runCatching {
            val uri = URI(url)
            uri.scheme == "https" && uri.userInfo == null && uri.port == -1 &&
                uri.host?.lowercase() in setOf("libvio.pw", "www.libvio.pw", "libvio.io", "www.libvio.io")
        }.getOrDefault(false)
    }
}
