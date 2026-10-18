package com.wuming.tv.core.playback

import java.util.function.BiFunction

/** Detects short, same-format encoded insertions with a truncated tail, a reset
 * timeline and exact programme resumption. Independent of the resolution gate. */
object HlsTimestampInsertionFilter {
    @JvmStatic
    fun filter(base: String, playlist: String?, probe: HlsResolutionAdFilter.Probe?,
               resolver: BiFunction<String, String, String>): HlsResolutionAdFilter.Result =
        HlsResolutionAdFilter.filterInternal(base, playlist, probe, resolver, true)
}
