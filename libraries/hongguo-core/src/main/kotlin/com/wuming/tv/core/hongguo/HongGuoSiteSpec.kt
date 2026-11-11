package com.wuming.tv.core.hongguo

import java.net.URI

/** The website provider identity, distinct from the official HongGuo application's API. */
data class HongGuoSiteSpec(val key: String, val name: String, val categories: List<String> = emptyList()) {
    companion object {
        const val BASE_URL = "https://www.hongguodj.cc/"
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Mobile Safari/537.36"
        @JvmStatic fun matches(api: String) = api == "csp_HongGuo" || api == "builtin_hongguo"
        fun isSiteUrl(value: String): Boolean = runCatching {
            val uri = URI(value)
            uri.scheme == "https" && uri.host in setOf("www.hongguodj.cc", "hongguodj.cc") &&
                uri.userInfo == null && uri.port == -1 && value.none(Char::isISOControl)
        }.getOrDefault(false)
    }
}
