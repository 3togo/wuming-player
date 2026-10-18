package com.wuming.tv.core.source.config

/** Selects only an explicitly preferred bundled source, never an unlisted fallback. */
object BundledStartupPolicy {
    data class Source(val name: String, val location: String)

    @JvmStatic
    fun select(sources: List<Source>, preferredName: String): Source? =
        sources.firstOrNull { it.name == preferredName && it.location.isNotBlank() }
}
