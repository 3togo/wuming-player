package com.wuming.tv.core.catalog

import java.net.URI

/** JPJ's advertised image mirrors share poster paths; unrelated providers are never rewritten. */
object CatalogPosterMirrors {
    private val hosts = listOf("img.cqkgy.com", "img.ypfbj.com", "img.bptpw.com")

    fun candidates(location: String): List<String> {
        val uri = runCatching { URI(location) }.getOrNull() ?: return listOf(location)
        if (uri.host?.lowercase() !in hosts || uri.userInfo != null ||
            uri.port != -1 || uri.scheme?.lowercase() !in listOf("http", "https")) return listOf(location)
        return listOf(location) + hosts.filterNot { it.equals(uri.host, true) }.map { host ->
            // Preserve escaped path and query exactly; never decode signed URL components.
            "https://" + host + (uri.rawPath ?: "") + (uri.rawQuery?.let { "?" + it } ?: "")
        }
    }
}
