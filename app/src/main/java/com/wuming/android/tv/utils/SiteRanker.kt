package com.wuming.android.tv.utils

import com.wuming.android.tv.api.config.SiteSourceAdapter
import com.wuming.android.tv.bean.Site
import com.wuming.android.tv.bean.SitePerformance
import com.wuming.android.tv.db.SitePerformanceRepository
import com.wuming.tv.core.source.SourceMetric

class SiteRanker private constructor() {

    fun rankByPerformance(sites: List<Site>?): List<Site> {
        if (sites.isNullOrEmpty()) return sites ?: emptyList()
        return try {
            val performanceMap = loadPerformanceData(sites)
            SiteSourceAdapter.ranking().rankByPerformance(sites, metrics(performanceMap))
        } catch (e: Exception) {
            sites
        }
    }

    fun rankByReliability(sites: List<Site>?): List<Site> {
        if (sites.isNullOrEmpty()) return sites ?: emptyList()
        val performanceMap = loadPerformanceData(sites)
        return SiteSourceAdapter.ranking().rankByReliability(sites, metrics(performanceMap))
    }

    fun rankBySpeed(sites: List<Site>?): List<Site> {
        if (sites.isNullOrEmpty()) return sites ?: emptyList()
        val performanceMap = loadPerformanceData(sites)
        return SiteSourceAdapter.ranking().rankBySpeed(sites, metrics(performanceMap))
    }

    fun getBestSite(sites: List<Site>?): Site? {
        if (sites.isNullOrEmpty()) return null
        if (sites.size == 1) return sites[0]
        val ranked = rankByPerformance(sites)
        return ranked.getOrNull(0)
    }

    fun getReliableSites(sites: List<Site>?, minReliability: Double): List<Site> {
        if (sites.isNullOrEmpty()) return ArrayList()
        val performanceMap = loadPerformanceData(sites)
        return SiteSourceAdapter.ranking().reliableSources(sites, metrics(performanceMap), minReliability)
    }

    fun getRankingInfo(siteKey: String): RankingInfo {
        val info = RankingInfo()
        info.siteKey = siteKey
        try {
            val perf = SitePerformanceRepository.findBySiteKey(siteKey)
            if (perf != null) {
                info.hasData = true
                info.totalRequests = perf.totalRequests
                info.successCount = perf.successCount
                info.deadLinkCount = perf.deadLinkCount
                info.successRate = perf.successRate
                info.deadLinkRate = perf.deadLinkRate
                info.avgSpeed = perf.avgSpeed
                info.formattedSpeed = perf.formattedSpeed
                info.reliabilityScore = perf.reliabilityScore
                info.performanceScore = perf.performanceScore
                info.isReliable = perf.isReliable
            }
        } catch (e: Exception) {
        }
        return info
    }

    fun loadPerformanceData(sites: List<Site>?): Map<String, SitePerformance> {
        if (sites.isNullOrEmpty()) return HashMap()
        val keys = HashSet<String>()
        for (site in sites) keys.add(site.key)
        return SitePerformanceRepository.findBySiteKeys(keys)
    }

    private fun metrics(performanceMap: Map<String, SitePerformance>): Map<String, SourceMetric> {
        val result = HashMap<String, SourceMetric>()
        performanceMap.forEach { (key, performance) ->
            result[key] = SourceMetric(
                performance.totalRequests, performance.avgSpeed,
                performance.reliabilityScore, performance.performanceScore
            )
        }
        return result
    }

    class RankingInfo {
        @JvmField
        var siteKey: String? = null
        @JvmField
        var hasData: Boolean = false
        @JvmField
        var totalRequests: Long = 0
        @JvmField
        var successCount: Long = 0
        @JvmField
        var deadLinkCount: Long = 0
        @JvmField
        var successRate: Double = 0.0
        @JvmField
        var deadLinkRate: Double = 0.0
        @JvmField
        var avgSpeed: Double = 0.0
        @JvmField
        var formattedSpeed: String = "N/A"
        @JvmField
        var reliabilityScore: Double = 0.0
        @JvmField
        var performanceScore: Double = 0.0
        @JvmField
        var isReliable: Boolean = false

        override fun toString(): String {
            if (!hasData) {
                return "$siteKey: No data available"
            }
            return String.format(
                java.util.Locale.ROOT,
                "%s: Success=%.1f%%, DeadLinks=%.1f%%, Speed=%s, Score=%.2f",
                siteKey, successRate, deadLinkRate, formattedSpeed, performanceScore
            )
        }
    }

    companion object {
        @Volatile
        private var instance: SiteRanker? = null

        @JvmStatic
        fun get(): SiteRanker {
            if (instance == null) {
                synchronized(SiteRanker::class.java) {
                    if (instance == null) instance = SiteRanker()
                }
            }
            return instance!!
        }
    }
}
