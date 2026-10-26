package com.wuming.tv.core.source

/** Storage limits shared by Android P2P adapters. Unknown free space is not treated as zero. */
object P2pCachePolicy {
    const val MAX_CACHE_BYTES: Long = 2L * 1024 * 1024 * 1024
    const val MIN_FREE_BYTES: Long = 512L * 1024 * 1024

    @JvmStatic
    fun underPressure(cacheBytes: Long, freeBytes: Long?): Boolean =
        cacheBytes >= MAX_CACHE_BYTES || (freeBytes != null && freeBytes < MIN_FREE_BYTES)

    @JvmStatic
    fun canStart(freeBytes: Long?): Boolean = freeBytes == null || freeBytes >= MIN_FREE_BYTES
}
