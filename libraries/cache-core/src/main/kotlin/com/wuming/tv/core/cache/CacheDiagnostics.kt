package com.wuming.tv.core.cache

/** Snapshot of cache statistics for a single module, used by the diagnostics panel. */
data class CacheStats(
    val moduleName: String,
    val size: Int,
    val capacity: Int,
    val hitCount: Long,
    val missCount: Long,
    val evictionCount: Long,
) {
    val hitRate: Double get() = if (hitCount + missCount == 0L) 0.0 else hitCount.toDouble() / (hitCount + missCount)
    val usageRatio: Double get() = if (capacity == 0) 0.0 else size.toDouble() / capacity
}

/** Collects cache statistics from all registered modules for diagnostics. */
object CacheDiagnostics {
    private val collectors: MutableList<() -> CacheStats> = mutableListOf()

    @JvmStatic
    fun register(collector: () -> CacheStats) {
        collectors.add(collector)
    }

    @JvmStatic
    fun snapshot(): List<CacheStats> = collectors.map { it() }

    @JvmStatic
    fun totalSize(): Int = snapshot().sumOf { it.size }

    @JvmStatic
    fun totalCapacity(): Int = snapshot().sumOf { it.capacity }

    @JvmStatic
    fun overallHitRate(): Double {
        val stats = snapshot()
        val totalHits = stats.sumOf { it.hitCount }
        val totalMisses = stats.sumOf { it.missCount }
        return if (totalHits + totalMisses == 0L) 0.0 else totalHits.toDouble() / (totalHits + totalMisses)
    }
}