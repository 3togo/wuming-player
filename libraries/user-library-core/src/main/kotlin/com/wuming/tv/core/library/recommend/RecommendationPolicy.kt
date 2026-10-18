package com.wuming.tv.core.library.recommend

import com.wuming.tv.core.catalog.RecommendationCandidate
import com.wuming.tv.core.library.ContentKeyCodec
import java.util.concurrent.TimeUnit

/**
 * Platform-neutral recommendation ranking. Ported verbatim from the Android
 * [com.wuming.android.tv.recommendation.RecommendationEngine] so the desktop client
 * shares the exact same preference-learning behaviour. The Android class keeps only
 * threading, caching, and persistence; the desktop client provides its own record store.
 */
object RecommendationPolicy {
    private val PROFILE_WINDOW_MS = TimeUnit.DAYS.toMillis(365)
    private const val MAX_RECORDS = 2000

    @JvmStatic
    fun needsProfile(candidates: List<RecommendationCandidate>?): Boolean =
        candidates != null && candidates.size >= 2

    @JvmStatic
    fun buildProfile(records: List<ViewingRecord>, nowMillis: Long): RecommendationProfile =
        RecommendationProfile.from(records, nowMillis, PROFILE_WINDOW_MS, MAX_RECORDS)

    @JvmStatic
    fun buildProfile(
        records: List<ViewingRecord>,
        nowMillis: Long,
        favorites: List<FavoriteSignal>?,
    ): RecommendationProfile =
        RecommendationProfile.from(records, nowMillis, PROFILE_WINDOW_MS, MAX_RECORDS, favorites)

    @JvmStatic
    fun rank(
        candidates: List<RecommendationCandidate>,
        siteKey: String,
        profile: RecommendationProfile,
    ): List<RecommendationCandidate> {
        if (candidates.size < 2) return ArrayList(candidates)
        if (!profile.hasSignal) return ArrayList(candidates)
        val scored = candidates.mapIndexed { index, candidate ->
            // Penalty identity mirrors the original engine: siteKey + SYMBOL + item id.
            val key = value(siteKey) + ContentKeyCodec.DEFAULT_DELIMITER + candidate.itemId
            var score = profile.score(candidate)
            score -= when {
                profile.completedKeys.contains(key) -> 1000.0
                profile.watchedKeys.contains(key) -> 25.0
                else -> 0.0
            }
            Scored(candidate, score, index)
        }
        return scored
            .sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.originalIndex })
            .map { it.candidate }
    }

    private data class Scored(
        val candidate: RecommendationCandidate,
        val score: Double,
        val originalIndex: Int,
    )

    private fun value(value: String?): String = value ?: ""
}
