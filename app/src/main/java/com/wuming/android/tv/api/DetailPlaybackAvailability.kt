package com.wuming.android.tv.api

import com.wuming.android.tv.bean.Vod

/** Adapts provider detail beans to cache admission and the Android playback screen. */
object DetailPlaybackAvailability {
    @JvmStatic
    fun hasPlayableEpisodes(vod: Vod?): Boolean = com.wuming.tv.core.catalog.DetailAvailabilityPolicy.hasPlayableEpisodes(urls(vod))

    @JvmStatic
    fun canCache(vod: Vod?): Boolean = com.wuming.tv.core.catalog.DetailAvailabilityPolicy.canCache(vod != null,
        vod?.isFolder() == true, vod?.isAction() == true) { hasPlayableEpisodes(vod) }

    private fun urls(vod: Vod?): Iterable<String> = vod?.getFlags()?.asSequence()
        ?.flatMap { flag -> flag.getEpisodes().asSequence().map { it.getUrl() } }?.asIterable() ?: emptyList()
}
