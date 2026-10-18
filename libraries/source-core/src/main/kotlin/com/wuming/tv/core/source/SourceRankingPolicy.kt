package com.wuming.tv.core.source

/** Ranks sources from supplied metrics without owning persistence or threading. */
class SourceRankingPolicy<T>(private val access: SourceAccess<T>) {

    fun rankByPerformance(sources: List<T>?, metrics: Map<String, SourceMetric>): List<T> =
        rank(sources) { metric(it, metrics)?.performanceScore() ?: DEFAULT_UNTESTED_SCORE }

    fun rankByReliability(sources: List<T>?, metrics: Map<String, SourceMetric>): List<T> =
        rank(sources) { metric(it, metrics)?.reliabilityScore() ?: DEFAULT_UNTESTED_SCORE }

    fun rankBySpeed(sources: List<T>?, metrics: Map<String, SourceMetric>): List<T> =
        rank(sources) { metric(it, metrics)?.averageSpeed() ?: Double.NEGATIVE_INFINITY }

    fun reliableSources(
        sources: List<T>?,
        metrics: Map<String, SourceMetric>,
        minimumReliability: Double,
    ): List<T> = rankByPerformance(sources, metrics).filter {
        (metric(it, metrics)?.reliabilityScore() ?: DEFAULT_UNTESTED_SCORE) >= minimumReliability
    }

    private fun rank(sources: List<T>?, score: (T) -> Double): List<T> =
        sources.orEmpty().sortedByDescending(score)

    private fun metric(source: T, metrics: Map<String, SourceMetric>): SourceMetric? {
        val metric = metrics[access.key(source)] ?: return null
        return metric.takeIf { it.totalRequests() >= MIN_REQUESTS_FOR_RANKING }
    }

    companion object {
        const val MIN_REQUESTS_FOR_RANKING = 3L
        const val DEFAULT_UNTESTED_SCORE = 0.2
    }
}
