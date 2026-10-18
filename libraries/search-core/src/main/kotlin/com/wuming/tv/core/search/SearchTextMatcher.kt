package com.wuming.tv.core.search

import com.wuming.tv.core.base.native.NativeLoader
import java.util.Locale

/** Deterministic text matching primitives shared by search planners and clients. */
object SearchTextMatcher {

    private val nativeAvailable: Boolean get() = NativeLoader.available

    @JvmStatic
    fun fuzzyMatch(text: String?, query: String?): Boolean {
        if (text == null || query == null) return false
        if (nativeAvailable) return nativeFuzzyMatch(text, query)
        if (query.isEmpty()) return true
        if (query.length > text.length) return false
        var queryIndex = 0
        for (character in text) {
            if (queryIndex < query.length && character == query[queryIndex]) queryIndex++
        }
        return queryIndex == query.length
    }

    @JvmStatic
    fun levenshteinDistance(first: String?, second: String?): Int {
        val a = first ?: ""
        val b = second ?: ""
        if (nativeAvailable) return nativeLevenshteinDistance(a, b)
        var shorter = a
        var longer = b
        if (shorter.length > longer.length) shorter = longer.also { longer = shorter }
        var previous = IntArray(shorter.length + 1) { it }
        var current = IntArray(shorter.length + 1)
        for (longIndex in 1..longer.length) {
            current[0] = longIndex
            for (shortIndex in 1..shorter.length) {
                val cost = if (shorter[shortIndex - 1] == longer[longIndex - 1]) 0 else 1
                current[shortIndex] = minOf(
                    previous[shortIndex] + 1,
                    current[shortIndex - 1] + 1,
                    previous[shortIndex - 1] + cost,
                )
            }
            previous = current.also { current = previous }
        }
        return previous[shorter.length]
    }

    @JvmStatic
    fun similarity(first: String?, second: String?): Double {
        if (first == null || second == null) return 0.0
        if (first == second) return 1.0
        if (nativeAvailable) return nativeSimilarity(first, second)
        val maximum = maxOf(first.length, second.length)
        return if (maximum == 0) 1.0 else 1.0 - levenshteinDistance(first, second).toDouble() / maximum
    }

    @JvmStatic
    fun containsAllWords(text: String?, query: String?): Boolean {
        if (text == null || query == null) return false
        if (nativeAvailable) return nativeContainsAllWords(text, query)
        val haystack = text.lowercase(Locale.ROOT)
        return words(query).all(haystack::contains)
    }

    @JvmStatic
    fun containsAnyWord(text: String?, query: String?): Boolean {
        if (text == null || query == null) return false
        if (nativeAvailable) return nativeContainsAnyWord(text, query)
        val haystack = text.lowercase(Locale.ROOT)
        return words(query).any(haystack::contains)
    }

    @JvmStatic
    fun wordSimilarity(first: String?, second: String?): Double {
        if (first == null || second == null) return 0.0
        if (nativeAvailable) return nativeWordSimilarity(first, second)
        val firstWords = words(first).toSet()
        val secondWords = words(second).toSet()
        val union = firstWords union secondWords
        return if (union.isEmpty()) 1.0 else (firstWords intersect secondWords).size.toDouble() / union.size
    }

    private fun words(value: String): List<String> = value
        .lowercase(Locale.ROOT)
        .split(Regex("\\s+"))
        .filter(String::isNotEmpty)

    @JvmStatic
    private external fun nativeLevenshteinDistance(first: String, second: String): Int

    @JvmStatic
    private external fun nativeSimilarity(first: String, second: String): Double

    @JvmStatic
    private external fun nativeFuzzyMatch(text: String, query: String): Boolean

    @JvmStatic
    private external fun nativeContainsAllWords(text: String, query: String): Boolean

    @JvmStatic
    private external fun nativeContainsAnyWord(text: String, query: String): Boolean

    @JvmStatic
    private external fun nativeWordSimilarity(first: String, second: String): Double
}
