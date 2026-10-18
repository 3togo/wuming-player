package com.wuming.android.tv.bean

/** Android compatibility facade for the shared episode-label policy. */
object EpisodeDisplayName {
    @JvmField val FALLBACK: String = com.wuming.tv.core.catalog.EpisodeDisplayName.FALLBACK

    @JvmStatic
    fun format(value: String?): String = com.wuming.tv.core.catalog.EpisodeDisplayName.format(value)
}
