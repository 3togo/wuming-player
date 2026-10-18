package com.wuming.android.tv.bean

import com.wuming.tv.core.catalog.EpisodeShortNameFormatter

object EpisodeShortName {

    private val CACHE = ShortNameCache(
        "EpisodeShortName", "episode_short_names", 512
    ) { original, vodName -> formatWithVod(original, vodName) }

    @JvmStatic
    fun shorten(episodeName: String, vodName: String): String =
        CACHE.compute(episodeName, vodName)

    @JvmStatic
    fun lookup(name: String): String = CACHE.lookup(name)

    @JvmStatic
    fun formatWithVod(original: String, vodName: String): String =
        EpisodeShortNameFormatter.formatWithVod(original, vodName)

    @JvmStatic
    fun replaceVodPart(episode: String, vodNormalized: String): String =
        EpisodeShortNameFormatter.replaceVodPart(episode, vodNormalized)

    @JvmStatic
    fun indexOfIgnoringSpace(haystack: String, needle: String): Int =
        EpisodeShortNameFormatter.indexOfIgnoringSpace(haystack, needle)

    @JvmStatic
    fun matchEndIgnoringSpace(haystack: String, start: Int, needle: String): Int =
        EpisodeShortNameFormatter.matchEndIgnoringSpace(haystack, start, needle)
}
