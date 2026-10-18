package com.wuming.android.tv.library

import com.wuming.android.tv.bean.Episode
import com.wuming.tv.core.library.PlaylistSelectionPolicy

class PlaylistEpisodeSelection @JvmOverloads constructor(
    flag: String? = "",
    episodeNames: List<String>? = emptyList(),
    episodeUrls: List<String>? = emptyList(),
) {
    private val policy = PlaylistSelectionPolicy(flag, episodeNames, episodeUrls)

    fun getFlag(): String = policy.getFlag()

    fun getEpisodeNames(): List<String> = policy.getEpisodeNames()

    fun size(): Int = policy.size()

    fun getEpisodeUrls(): List<String> = policy.getEpisodeUrls()

    fun includes(candidateFlag: String?, episode: Episode?): Boolean {
        if (episode == null) return false
        return policy.includes(candidateFlag, episode.getName(), episode.getUrl())
    }

    fun filter(candidateFlag: String?, episodes: List<Episode>?): List<Episode> {
        return policy.filter(candidateFlag, episodes, Episode::getName, Episode::getUrl)
    }

    internal fun initialEpisode(): Pair<String, String>? = policy.initialEpisode()
}
