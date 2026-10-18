package com.wuming.tv.core.epg

import com.wuming.tv.core.base.native.NativeLoader
import java.text.Normalizer
import java.util.Locale
import kotlin.math.max

data class ChannelNameMatch(
    val source: String,
    val candidate: String,
    val score: Double,
    val kind: Kind,
) {
    enum class Kind { EXACT, CONTAINS, FUZZY }
}

object ChannelNameMatcher {

    private val nativeAvailable: Boolean get() = NativeLoader.available

    private val qualityTokens = Regex(
        "(?i)(?<![a-z0-9])(?:fhd|uhd|hd|sd|4k|8k|live)(?![a-z0-9])|高清|超清|标清|直播",
    )
    private val trailingType = Regex("(?:卫视|电视台|频道)$")
    private val separators = Regex("[^\\p{L}\\p{N}]+")
    private val digits = Regex("\\d+")

    @JvmStatic
    @JvmOverloads
    fun findBest(
        sourceNames: Collection<String>,
        candidateNames: Collection<String>,
        minimumScore: Double = 0.86,
    ): ChannelNameMatch? {
        require(minimumScore in 0.0..1.0) { "minimumScore must be between 0 and 1" }
        return sourceNames.asSequence()
            .filter(String::isNotBlank)
            .flatMap { source ->
                candidateNames.asSequence().filter(String::isNotBlank).map { candidate ->
                    match(source, candidate)
                }
            }
            .filter { it.score >= minimumScore }
            .maxWithOrNull(compareBy<ChannelNameMatch> { it.score }.thenByDescending { it.candidate })
    }

    @JvmStatic
    fun match(source: String, candidate: String): ChannelNameMatch {
        val left = normalize(source)
        val right = normalize(candidate)
        if (left.isEmpty() || right.isEmpty()) return ChannelNameMatch(source, candidate, 0.0, ChannelNameMatch.Kind.FUZZY)
        if (left == right) return ChannelNameMatch(source, candidate, 1.0, ChannelNameMatch.Kind.EXACT)

        val leftNumbers = digits.findAll(left).map { it.value }.toSet()
        val rightNumbers = digits.findAll(right).map { it.value }.toSet()
        if (leftNumbers.isNotEmpty() && rightNumbers.isNotEmpty() && leftNumbers != rightNumbers) {
            return ChannelNameMatch(source, candidate, 0.0, ChannelNameMatch.Kind.FUZZY)
        }
        if (left.contains(right) || right.contains(left)) {
            return ChannelNameMatch(source, candidate, 0.90, ChannelNameMatch.Kind.CONTAINS)
        }

        val compactLeft = left.replace(" ", "")
        val compactRight = right.replace(" ", "")
        val score = levenshteinSimilarity(compactLeft, compactRight)
        return ChannelNameMatch(source, candidate, score, ChannelNameMatch.Kind.FUZZY)
    }

    @JvmStatic
    fun normalize(value: String): String {
        if (nativeAvailable) return nativeNormalize(value)
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace(qualityTokens, " ")
            .replace(separators, " ")
            .trim()
            .replace(Regex("\\s+"), " ")
            .replace(trailingType, "")
            .trim()
    }

    private fun levenshteinSimilarity(left: String, right: String): Double {
        if (left == right) return 1.0
        if (left.isEmpty() || right.isEmpty()) return 0.0
        if (nativeAvailable) return nativeLevenshteinSimilarity(left, right)
        var previous = IntArray(right.length + 1) { it }
        var current = IntArray(right.length + 1)
        for (i in left.indices) {
            current[0] = i + 1
            for (j in right.indices) {
                val substitution = previous[j] + if (left[i] == right[j]) 0 else 1
                current[j + 1] = minOf(previous[j + 1] + 1, current[j] + 1, substitution)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return 1.0 - previous[right.length].toDouble() / max(left.length, right.length)
    }

    @JvmStatic
    private external fun nativeLevenshteinSimilarity(left: String, right: String): Double

    @JvmStatic
    private external fun nativeNormalize(value: String): String
}
