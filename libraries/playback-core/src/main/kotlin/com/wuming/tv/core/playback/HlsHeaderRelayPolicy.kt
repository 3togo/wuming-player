package com.wuming.tv.core.playback

/** Determines whether HTTP headers beyond User-Agent and Referer require a relay proxy. */
object HlsHeaderRelayPolicy {

    @JvmStatic
    fun requiresHeaderRelay(headers: Map<String, String>): Boolean = headers.keys.any { name ->
        !name.equals("User-Agent", true) && !name.equals("Referer", true)
    }
}
