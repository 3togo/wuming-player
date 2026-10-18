package com.wuming.tv.core.playback

import java.util.Collections

/**
 * Immutable snapshot of an episodic cast queue.
 *
 * The queue stores provider episode IDs rather than resolved media URLs.
 * URLs are resolved immediately before an episode is cast because provider
 * URLs, cookies, and headers can expire while the previous episode is playing.
 */
class CastQueue(
    items: List<Item>,
    private val currentIndex: Int,
    private val siteKey: String?,
    private val flag: String?,
    private val audioTrackFormat: String?
) {
    private val items: List<Item> = Collections.unmodifiableList(ArrayList(items))

    fun items(): List<Item> = items
    fun currentIndex(): Int = currentIndex
    fun siteKey(): String? = siteKey
    fun flag(): String? = flag
    fun audioTrackFormat(): String? = audioTrackFormat

    fun canAutoAdvance(): Boolean {
        return hasCurrent()
            && currentIndex + 1 < items.size
            && siteKey != null
            && siteKey.isNotEmpty()
            && flag != null
            && flag.isNotEmpty()
    }

    fun hasCurrent(): Boolean = currentIndex >= 0 && currentIndex < items.size

    fun current(): Item? = if (hasCurrent()) items[currentIndex] else null

    fun withAudioTrackFormat(format: String?): CastQueue {
        return CastQueue(items, currentIndex, siteKey, flag, format)
    }

    data class Item(val name: String, val url: String) {
        @JvmName("name")
        fun nameAccessor(): String = name
        @JvmName("url")
        fun urlAccessor(): String = url
    }
}
