package com.wuming.android.tv.bean

/**
 * Persistent original→short cache for VOD titles.
 *
 * Warm-up happens once when the user opens a video detail page ([shorten]),
 * which is the natural "chosen" moment — list binders then read via [lookup]
 * cache-only (zero regex). The stored mapping doubles as a corpus for later refinement.
 *
 * Separate from EpisodeShortName because the vocabularies and warm-up moments differ.
 */
object VodShortName {
    private val CACHE = ShortNameCache(
        "VodShortName", "vod_short_names", 768
    ) { original, _ -> VodDisplayName.format(original) }

    /** Warm the cache (chosen moment). Computes only on cache miss; otherwise returns the cached value. */
    @JvmStatic
    fun shorten(name: String?): String = CACHE.compute(name ?: "", "")

    /** Cache-only read (render path). Returns the raw name when this VOD was never chosen. */
    @JvmStatic
    fun lookup(name: String?): String = CACHE.lookup(name ?: "")
}
