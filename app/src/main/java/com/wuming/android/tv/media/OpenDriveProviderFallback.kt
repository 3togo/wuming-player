package com.wuming.android.tv.media

import com.wuming.android.tv.bean.Episode
import com.wuming.android.tv.bean.Flag
import com.wuming.android.tv.cloud.opendrive.OpenDriveSpider
import java.util.Locale

object OpenDriveProviderFallback {

    @JvmStatic
    fun findBaidu(
        flags: List<Flag>?,
        currentFlag: Flag?,
        currentEpisode: Episode?,
        attemptedProvider: String,
        error: String?
    ): Candidate? {
        if (attemptedProvider != "uc" || !isUcSaveFailure(error) || flags == null || currentEpisode == null) return null
        for (flag in flags) {
            if (flag === currentFlag) continue
            val episode = flag.find(currentEpisode.getName(), true) ?: continue
            if (!sameEpisode(currentEpisode, episode)) continue
            if (OpenDriveSpider.routedProviderId(episode.getUrl()) == "baidu") {
                return Candidate(flag, episode)
            }
        }
        return null
    }

    internal fun isUcSaveFailure(error: String?): Boolean {
        val value = error?.lowercase(Locale.ROOT) ?: ""
        return value.contains("uc task request failed") ||
            value.contains("uc save request failed") ||
            value.contains("share/sharepage/save") ||
            (value.contains("code 32011") && value.contains("uc"))
    }

    private fun sameEpisode(expected: Episode, candidate: Episode): Boolean {
        if (expected.matchesName(candidate)) return true
        return expected.getNumber() >= 0 && expected.getNumber() == candidate.getNumber()
    }

    data class Candidate(@get:JvmName("flag") val flag: Flag, @get:JvmName("episode") val episode: Episode)
}
