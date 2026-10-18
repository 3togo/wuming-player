package com.wuming.tv.core.library.recommend

import com.wuming.tv.core.catalog.RecommendationCandidate
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Immutable, pure preference profile built from viewing records. Holds the weighted
 * token maps and the watched/completed identity sets; scoring is a pure function of a
 * [RecommendationCandidate]. No Android, threading, or persistence concerns here.
 */
class RecommendationProfile internal constructor(
    val watchedKeys: Set<String>,
    val completedKeys: Set<String>,
    val hasSignal: Boolean,
    private val types: Map<String, Double>,
    private val actors: Map<String, Double>,
    private val directors: Map<String, Double>,
    private val areas: Map<String, Double>,
    private val decades: Map<String, Double>,
    private val keywords: Map<String, Double>,
) {
    fun score(candidate: RecommendationCandidate): Double {
        var score = match(types, setOf(value(candidate.typeName))) * 4.0
        score += match(actors, tokens(candidate.actor)) * 3.0
        score += match(directors, tokens(candidate.director)) * 2.5
        score += match(areas, tokens(candidate.area)) * 1.5
        score += match(decades, setOf(decade(candidate.year))) * 0.5
        score += match(keywords, keywordTokens(candidate.content))
        return score
    }

    companion object {
        private const val FAVORITE_WEIGHT = 2.0

        fun from(
            records: List<ViewingRecord>,
            nowMillis: Long,
            windowMillis: Long,
            maxRecords: Int,
            favorites: List<FavoriteSignal>? = null,
        ): RecommendationProfile {
            val windowed = records
                .filter { elapsedSince(nowMillis, it.lastWatchedAtMillis) < windowMillis }
                .sortedByDescending { it.lastWatchedAtMillis }
                .take(maxRecords)
            val types = mutableMapOf<String, Double>()
            val actors = mutableMapOf<String, Double>()
            val directors = mutableMapOf<String, Double>()
            val areas = mutableMapOf<String, Double>()
            val decades = mutableMapOf<String, Double>()
            val keywords = mutableMapOf<String, Double>()
            val watchedKeys = mutableSetOf<String>()
            val completedKeys = mutableSetOf<String>()
            var hasSignal = false
            for (record in windowed) {
                watchedKeys.add(record.key)
                if (record.completed) completedKeys.add(record.key)
                if (record.watchedDurationMillis < TimeUnit.MINUTES.toMillis(1) && !record.completed) continue
                val minutes = Math.min(240.0, record.watchedDurationMillis / 60_000.0)
                val ageDays = elapsedSince(nowMillis, record.lastWatchedAtMillis) /
                    TimeUnit.DAYS.toMillis(1).toDouble()
                var weight = Math.log1p(minutes) * Math.pow(0.5, ageDays / 120.0)
                if (record.completed) weight *= 1.2
                add(types, record.typeName, weight)
                addMany(actors, record.actor, weight)
                addMany(directors, record.director, weight)
                addMany(areas, record.area, weight)
                add(decades, decade(record.year), weight)
                addKeywords(keywords, record.content, weight * 0.25)
                hasSignal = hasSignal || weight > 0
            }
            for (favorite in favorites ?: emptyList()) {
                watchedKeys.add(favorite.key)
                add(types, favorite.typeName, FAVORITE_WEIGHT)
                addMany(actors, favorite.actor, FAVORITE_WEIGHT)
                addMany(directors, favorite.director, FAVORITE_WEIGHT)
                addMany(areas, favorite.area, FAVORITE_WEIGHT)
                add(decades, decade(favorite.year), FAVORITE_WEIGHT)
                addKeywords(keywords, favorite.content, FAVORITE_WEIGHT * 0.25)
                hasSignal = true
            }
            return RecommendationProfile(
                watchedKeys, completedKeys, hasSignal,
                types, actors, directors, areas, decades, keywords,
            )
        }
    }
}

/** Future timestamps are treated as current; subtraction cannot overflow on corrupt records. */
private fun elapsedSince(nowMillis: Long, timestampMillis: Long): Long = when {
    timestampMillis >= nowMillis -> 0L
    timestampMillis < 0L && nowMillis > Long.MAX_VALUE + timestampMillis -> Long.MAX_VALUE
    else -> nowMillis - timestampMillis
}

private fun match(weights: Map<String, Double>, tokens: Set<String>): Double {
    var score = 0.0
    for (token in tokens) score += weights.getOrDefault(normalize(token), 0.0)
    return score
}

private fun tokens(text: String): Set<String> {
    val result = mutableSetOf<String>()
    for (token in value(text).split("[\\s,，、/|;；·]+".toRegex())) {
        val normalized = normalize(token)
        if (normalized.length >= 2) result.add(normalized)
    }
    return result
}

private fun keywordTokens(text: String): Set<String> {
    val result = mutableSetOf<String>()
    for (token in value(text).split("[^\\p{L}\\p{N}]+".toRegex())) {
        val normalized = normalize(token)
        if (normalized.length in 3..24) result.add(normalized)
    }
    return result
}

private fun decade(year: String): String {
    val digits = value(year).replace("[^0-9]".toRegex(), "")
    return try {
        val value = digits.toInt()
        if (value in 1900..2100) (value / 10 * 10).toString() else ""
    } catch (_: NumberFormatException) {
        ""
    }
}

private fun normalize(value: String?): String = value(value).trim().lowercase(Locale.ROOT)

private fun value(value: String?): String = value ?: ""

private fun add(weights: MutableMap<String, Double>, token: String?, weight: Double) {
    val normalized = normalize(token)
    if (normalized.isNotEmpty()) weights[normalized] = weights.getOrDefault(normalized, 0.0) + weight
}

private fun addMany(weights: MutableMap<String, Double>, text: String?, weight: Double) {
    for (token in tokens(value(text))) add(weights, token, weight)
}

private fun addKeywords(weights: MutableMap<String, Double>, text: String?, weight: Double) {
    var count = 0
    for (token in keywordTokens(value(text))) {
        add(weights, token, weight)
        if (++count == 20) break
    }
}
