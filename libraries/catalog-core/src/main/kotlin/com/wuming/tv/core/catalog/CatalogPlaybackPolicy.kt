package com.wuming.tv.core.catalog

import java.net.URI

object CatalogPlaybackPolicy {

    @JvmStatic
    fun isHttpRelayCandidate(value: String): Boolean {
        if (value.isBlank() || value.any(Character::isISOControl)) return false
        return runCatching {
            val uri = URI(value)
            (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
                !uri.host.isNullOrBlank()
        }.getOrDefault(false)
    }

    @JvmStatic
    fun requiresInteractiveResolution(providerId: String): Boolean {
        val value = providerId.trimStart()
        return value.startsWith('{') || value.startsWith('[')
    }
}
