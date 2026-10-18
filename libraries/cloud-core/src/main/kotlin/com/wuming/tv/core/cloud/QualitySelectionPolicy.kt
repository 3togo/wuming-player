package com.wuming.tv.core.cloud

import com.google.gson.JsonObject

/** Platform-neutral representation of a single transcode candidate from any cloud provider. */
data class TranscodeCandidate(
    val url: String,
    val width: Int = 0,
    val height: Int = 0,
    val resolutionLabel: String = "",
    val accessible: Boolean = true,
    val raw: JsonObject? = null,
)

/** Selects the best transcode candidate from a list, applying smart-quality bounds and ranking. */
interface QualitySelectionPolicy {

    /** Whether the flag string requests smart (auto) quality selection. */
    fun isSmartQuality(flag: String?): Boolean

    /** Whether the candidate falls within the smart-quality ceiling (e.g. 1080p). */
    fun isWithinSmartBounds(candidate: TranscodeCandidate): Boolean

    /** Rank a candidate for smart-quality selection — higher is better. */
    fun smartRank(candidate: TranscodeCandidate): Int

    /** Rank a candidate by resolution label — higher is better. */
    fun labelRank(label: String): Int

    /** Select the best candidate from [candidates] given [requestedResolution] ("auto" or a specific label). */
    fun select(candidates: List<TranscodeCandidate>, requestedResolution: String): TranscodeCandidate {
        val automatic = "auto".equals(requestedResolution, ignoreCase = true)
        var best: TranscodeCandidate? = null
        var bestRank = -1
        for (candidate in candidates) {
            if (candidate.url.isBlank()) continue
            if (!automatic && requestedResolution.equals(candidate.resolutionLabel, ignoreCase = true)) return candidate
            if (automatic && (!candidate.accessible || !isWithinSmartBounds(candidate))) continue
            val rank = if (automatic) smartRank(candidate) else labelRank(candidate.resolutionLabel)
            if (best == null || (automatic && rank > bestRank)) {
                best = candidate
                bestRank = rank
            }
        }
        if (best != null) return best
        if (automatic) throw OpenDriveFailure(providerKind(), OpenDriveFailure.Reason.TRANSCODE_UNAVAILABLE)
        throw java.io.IOException("${providerKind()} did not return a transcoded video")
    }

    /** Provider identifier for error messages. */
    fun providerKind(): String
}

/** Default implementation shared by providers that use a 1080p smart-quality ceiling. */
abstract class DefaultQualitySelectionPolicy : QualitySelectionPolicy {

    override fun isWithinSmartBounds(candidate: TranscodeCandidate): Boolean {
        if (candidate.width > 0 && candidate.height > 0) {
            val longer = maxOf(candidate.width, candidate.height)
            val shorter = minOf(candidate.width, candidate.height)
            return longer <= 1920 && shorter <= 1080
        }
        return setOf("super", "high", "normal", "low").contains(candidate.resolutionLabel.lowercase())
    }

    override fun smartRank(candidate: TranscodeCandidate): Int {
        if (candidate.width > 0 && candidate.height > 0) return candidate.width * candidate.height
        return when (candidate.resolutionLabel.lowercase()) {
            "super" -> 1920 * 1080
            "high" -> 1280 * 720
            "normal" -> 960 * 540
            "fhd" -> 1920 * 1080
            "hd" -> 1280 * 720
            "sd" -> 960 * 540
            "ld" -> 480 * 270
            else -> 480 * 270
        }
    }

    override fun labelRank(label: String): Int {
        return when (label.lowercase()) {
            "4k" -> 6
            "2k" -> 5
            "super" -> 4
            "high" -> 3
            "normal" -> 2
            "low" -> 1
            "fhd" -> 4
            "hd" -> 3
            "sd" -> 2
            "ld" -> 1
            else -> 0
        }
    }
}