package com.wuming.tv.core.playback

import java.util.function.BiFunction

/** Conservative structural detection of short, independently encoded post-rolls.
 * No URL, provider, title, segment hash or advertisement fingerprint is used. */
object HlsAppendedAdFilter {
    @JvmStatic
    fun filter(base: String, playlist: String?, probe: HlsResolutionAdFilter.Probe?,
               resolver: BiFunction<String, String, String>): HlsResolutionAdFilter.Result =
        HlsResolutionAdFilter.filterInternal(base, playlist, probe, resolver, false, appended = true)
}
