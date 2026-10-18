package com.wuming.tv.desktop.library

import com.wuming.tv.core.catalog.RecommendationCandidate
import com.wuming.tv.core.library.recommend.RecommendationPolicy

/**
 * Desktop recommendation ranking over the shared core policy. Given a site key and a list of
 * catalogue candidates, it returns them reordered by the learned viewing preference. When there are
 * too few candidates or no viewing signal yet, the input order is preserved.
 */
class DesktopRecommendations(
    private val records: () -> List<DesktopViewingRecord>,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    fun <T : RecommendationCandidate> rank(siteKey: String, candidates: List<T>): List<T> {
        val now = clock()
        if (!RecommendationPolicy.needsProfile(candidates)) return candidates
        val profile = RecommendationPolicy.buildProfile(records(), now)
        if (!profile.hasSignal) return candidates
        @Suppress("UNCHECKED_CAST")
        return RecommendationPolicy.rank(candidates, siteKey, profile).map { it as T }
    }
}
