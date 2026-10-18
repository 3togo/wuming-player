package com.wuming.tv.core.cache

/**
 * Heap-scaled budgets shared by in-memory caches and worker pools.
 *
 * The three tiers match the historical Android sizing: devices with at least 2 GiB of Dalvik
 * heap take the high value, at least 1 GiB takes the middle value, and everything else takes the
 * low value. Keeping the rule here means one measured ladder instead of a private copy per class.
 */
class MemoryBudget(private val maxMemoryBytes: Long) {

    init {
        if (maxMemoryBytes <= 0) throw IllegalArgumentException("Positive max memory required")
    }

    fun maxMemoryBytes(): Long = maxMemoryBytes

    fun maxMemoryMegabytes(): Long = maxMemoryBytes / MEGABYTE

    fun scale(low: Int, mid: Int, high: Int): Int {
        if (maxMemoryBytes >= HIGH_TIER_BYTES) return high
        if (maxMemoryBytes >= MID_TIER_BYTES) return mid
        return low
    }

    /** Byte budget for a cache which may hold the given ratio of the maximum heap. */
    fun weightedBytes(ratio: Double): Long {
        if (!(ratio > 0.0) || ratio > 1.0) {
            throw IllegalArgumentException("Weight ratio must be in (0, 1]")
        }
        return (maxMemoryBytes * ratio).toLong()
    }

    companion object {
        private const val MEGABYTE = 1024L * 1024L
        private const val HIGH_TIER_BYTES = 2048L * MEGABYTE
        private const val MID_TIER_BYTES = 1024L * MEGABYTE

        @JvmStatic
        fun current(): MemoryBudget = MemoryBudget(Runtime.getRuntime().maxMemory())
    }
}
