package com.wuming.tv.desktop.cache

import com.wuming.tv.desktop.net.embeddedHttpCandidates

/** A graph is downloaded into the runtime's private bundle; every remote member is revalidated. */
internal object DesktopModuleResources {
    fun load(location: String): ByteArray {
        var lastFailure: Exception? = null
        for (candidate in embeddedHttpCandidates(location)) {
            try {
                return ResourceDiskCache.modules.load(candidate, forceRefresh = true) { bytes ->
                    val text = bytes.toString(Charsets.UTF_8).trimStart()
                    require(text.isNotEmpty() && !text.startsWith("<!doctype html", true) && !text.startsWith("<html", true)) {
                        "JavaScript module returned an empty body or HTML."
                    }
                    true
                }.bytes
            } catch (failure: Exception) { lastFailure = failure }
        }
        throw lastFailure ?: IllegalArgumentException("Unable to load JavaScript module")
    }
}
