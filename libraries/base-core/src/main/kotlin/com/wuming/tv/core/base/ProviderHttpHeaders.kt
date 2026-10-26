package com.wuming.tv.core.base

/** Compatibility for providers that decorate their device User-Agent with Unicode glyphs. */
object ProviderHttpHeaders {
    @JvmStatic
    fun value(name: String, value: String): String {
        if (!name.equals("User-Agent", ignoreCase = true) || value.all { it.code < 128 }) return value
        require(HttpTokens.isHeaderValue(value)) { "Invalid provider User-Agent" }
        return value.filter { it.code in 32..126 }.trim().ifEmpty { "TVBox-Desktop/0.1" }
    }

    @JvmStatic
    fun values(headers: Map<String, String>): Map<String, String> =
        headers.mapValues { (name, value) -> value(name, value) }
}
