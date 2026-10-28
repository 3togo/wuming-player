package com.wuming.tv.core.playback

/** Exact media-host exceptions; catalog, key and other media traffic retain their proxy. */
object PlaybackProxyBypassPolicy {
    // JPJ VIP segments fail TLS through the configured HTTP proxy but work directly.
    private const val JPJ_VIDEO_HOST = "video.cqwqq.com"

    fun playerEnvironment(environment: Map<String, String>): Map<String, String> {
        if (listOf("http_proxy", "https_proxy", "all_proxy").none { key ->
                environment.entries.any { it.key.equals(key, true) && it.value.isNotBlank() }
            }) return environment
        val exclusions = linkedSetOf<String>()
        listOf("no_proxy", "NO_PROXY").forEach { key ->
            environment[key].orEmpty().split(',').map(String::trim)
                .filter(String::isNotEmpty).forEach(exclusions::add)
        }
        if (exclusions.none { it.equals(JPJ_VIDEO_HOST, true) }) exclusions.add(JPJ_VIDEO_HOST)
        val value = exclusions.joinToString(",")
        return environment + mapOf("no_proxy" to value, "NO_PROXY" to value)
    }
}
