package com.wuming.tv.core.source.config

import java.net.URL
import java.net.URLDecoder
import java.util.Locale

/** Keeps credential-bearing source addresses out of source-selection text. */
object ProtectedSourceUrl {
    const val HIDDEN_LABEL = "Protected source (URL hidden)"

    private val credentialKeys = setOf("access_token", "token", "api_key", "apikey", "auth", "password")

    @JvmStatic
    fun hasCredential(url: String): Boolean {
        val query = runCatching { URL(url).query }.getOrNull() ?: return false
        return query.split('&').any { parameter ->
            val key = runCatching { URLDecoder.decode(parameter.substringBefore('='), "UTF-8") }
                .getOrDefault("").lowercase(Locale.ROOT)
            key in credentialKeys
        }
    }

    @JvmStatic
    fun display(url: String): String = if (hasCredential(url)) HIDDEN_LABEL else url

}
