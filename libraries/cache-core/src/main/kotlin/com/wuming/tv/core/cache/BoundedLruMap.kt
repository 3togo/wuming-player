package com.wuming.tv.core.cache

import java.util.Collections
import java.util.LinkedHashMap

/**
 * Synchronized LRU map with a hard entry-count limit.
 *
 * Replaces the recurring `Collections.synchronizedMap(new LinkedHashMap(..., true) {
 *     override fun removeEldestEntry(...) = size > MAX
 * })` pattern with a single reusable factory.
 */
class BoundedLruMap<K, V> private constructor(
    private val maxEntries: Int,
    delegate: LinkedHashMap<K, V>
) : MutableMap<K, V> by delegate {

    companion object {
        @JvmStatic
        fun <K, V> create(maxEntries: Int): MutableMap<K, V> {
            val delegate = object : LinkedHashMap<K, V>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean {
                    return size > maxEntries
                }
            }
            return Collections.synchronizedMap(BoundedLruMap(maxEntries, delegate))
        }
    }
}
