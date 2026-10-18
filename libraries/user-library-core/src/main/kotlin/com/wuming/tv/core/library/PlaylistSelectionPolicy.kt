package com.wuming.tv.core.library

import java.util.Locale

class PlaylistSelectionPolicy @JvmOverloads constructor(
    flag: String? = "",
    episodeNames: List<String>? = emptyList(),
    episodeUrls: List<String>? = emptyList(),
) {
    private val flag: String = flag ?: ""
    private val episodeNames: List<String> = episodeNames?.let { ArrayList(it) } ?: ArrayList()
    private val episodeUrls: List<String> = episodeUrls?.let { ArrayList(it) } ?: ArrayList()

    fun getFlag(): String = flag

    fun getEpisodeNames(): List<String> = ArrayList(episodeNames)

    fun size(): Int = episodeNames.size

    fun getEpisodeUrls(): List<String> = ArrayList(episodeUrls)

    /** Optional initial playback metadata; host adapters only assign the resulting fields. */
    fun initialEpisode(): Pair<String, String>? = episodeNames.firstOrNull()?.let { flag to it }

    fun <T> filter(candidateFlag: String?, episodes: List<T>?, name: (T) -> String?, url: (T) -> String?): List<T> =
        episodes.orEmpty().filter { includes(candidateFlag, name(it), url(it)) }

    fun includes(candidateFlag: String?, episodeName: String?, episodeUrl: String?): Boolean {
        if (flag != (candidateFlag ?: "")) return false
        val candidateUrl = episodeUrl?.trim() ?: ""
        if (candidateUrl.isNotEmpty()) {
            for (url in episodeUrls) if (candidateUrl == url.trim()) return true
        }
        val candidate = normalize(episodeName)
        for (name in episodeNames) if (normalize(name) == candidate) return true
        return false
    }

    companion object {
        private fun normalize(value: String?): String = value?.trim()?.lowercase(Locale.ROOT)?.replace("\\s+".toRegex(), " ") ?: ""
    }
}
