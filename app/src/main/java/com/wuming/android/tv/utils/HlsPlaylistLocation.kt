package com.wuming.android.tv.utils

import com.wuming.tv.core.playback.HlsPlaylistLocation

/** Keeps the playlist transport location separate from the provider's rule scope.
 *  Delegates to the platform-neutral [HlsPlaylistLocation] in playback-core. */
object HlsPlaylistLocation {
    @JvmStatic
    fun shouldPurify(url: String?): Boolean = HlsPlaylistLocation.shouldPurify(url)

    @JvmStatic
    fun baseUrl(url: String): String = HlsPlaylistLocation.baseUrl(url)

    @JvmStatic
    fun ruleBaseUrl(url: String): String = HlsPlaylistLocation.ruleBaseUrl(url)

    @JvmStatic
    fun ruleBaseUrl(url: String, initialUrl: String): String =
        HlsPlaylistLocation.ruleBaseUrl(url, initialUrl)
}
