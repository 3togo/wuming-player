package com.wuming.tv.core.catalog

/** One playback request visits each remaining line once, matching the original episode by identity. */
class PlaybackLineFallback(lines: List<CatalogLine>, initialLine: Int, initialEpisode: Int) {
    data class Selection(val lineIndex: Int, val episodeIndex: Int)

    private val candidates: Iterator<Selection> = run {
        val originalLine = lines.getOrNull(initialLine)
        val episode = originalLine?.episodes?.getOrNull(initialEpisode)
        if (episode == null) emptyList<Selection>().iterator() else {
            val number = EpisodeMatchPolicy.number(episode.name)
            (1 until lines.size).mapNotNull { offset ->
                val index = (initialLine + offset) % lines.size
                val target = lines[index]
                val scores = target.episodes.map { candidate ->
                    EpisodeMatchPolicy.score(candidate.name, EpisodeMatchPolicy.number(candidate.name), episode.name, number)
                }
                val best = scores.maxOrNull() ?: 0
                val matches = scores.indices.filter { scores[it] == best && best > 0 }
                val matched = when {
                    matches.size == 1 -> matches.single()
                    best == 0 && number == -1 && originalLine.episodes.size == 1 && target.episodes.size == 1 &&
                        EpisodeMatchPolicy.number(target.episodes.single().name) == -1 -> 0
                    else -> null // Missing or ambiguous episodes must not silently play another part.
                }
                matched?.let { Selection(index, it) }
            }.iterator()
        }
    }

    fun next(): Selection? = if (candidates.hasNext()) candidates.next() else null
}
