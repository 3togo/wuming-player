package com.wuming.tv.core.library

/** Platform-neutral VOD history/favorite record shared by non-Android clients. */
data class VideoLibraryEntry(
    val catalogId: Int = 0,
    val sourceKey: String = "",
    val itemId: String = "",
    val name: String = "",
    val picture: String = "",
    val remarks: String = "",
    val year: String = "",
    val lineFlag: String = "",
    val episodeId: String = "",
    val episodeName: String = "",
    val updatedAtMillis: Long = 0,
    val positionMillis: Long = 0,
    val durationMillis: Long = 0,
) {
    val key: String get() = sourceKey + ContentKeyCodec.DEFAULT_DELIMITER + itemId
}

data class EpisodeSelectionIndex(val lineIndex: Int, val episodeIndex: Int)

/** Restores an episode by stable provider identifiers instead of fragile UI positions. */
object EpisodeSelectionPolicy {
    /** A series identifier alone is insufficient: each line/episode has its own timeline. */
    @JvmStatic
    fun resumePosition(saved: VideoLibraryEntry?, lineFlag: String, episodeId: String): Long =
        saved?.takeIf { it.lineFlag == lineFlag && it.episodeId == episodeId }
            ?.positionMillis?.coerceAtLeast(0) ?: 0L

    @JvmStatic
    fun restore(
        lineFlags: List<String>,
        episodeIds: List<List<String>>,
        savedLineFlag: String?,
        savedEpisodeId: String?,
    ): EpisodeSelectionIndex? {
        if (lineFlags.isEmpty() || episodeIds.isEmpty()) return null
        val lineIndex = lineFlags.indexOf(savedLineFlag).takeIf { it >= 0 } ?: 0
        val episodes = episodeIds.getOrNull(lineIndex).orEmpty()
        if (episodes.isEmpty()) return null
        val episodeIndex = episodes.indexOf(savedEpisodeId).takeIf { it >= 0 } ?: 0
        return EpisodeSelectionIndex(lineIndex, episodeIndex)
    }
}

/** Navigation uses the displayed episode order; callers may reverse that list independently. */
object EpisodeAdvancePolicy {
    enum class Direction { NEXT, PREVIOUS }

    @JvmStatic
    fun nextIndex(currentIndex: Int, episodeCount: Int): Int? =
        index(currentIndex, episodeCount, Direction.NEXT)

    @JvmStatic
    fun previousIndex(currentIndex: Int, episodeCount: Int): Int? =
        index(currentIndex, episodeCount, Direction.PREVIOUS)

    @JvmStatic
    fun playbackDirection(requestNext: Boolean, reversePlayback: Boolean): Direction =
        if (requestNext != reversePlayback) Direction.NEXT else Direction.PREVIOUS

    @JvmStatic
    fun index(currentIndex: Int, episodeCount: Int, direction: Direction): Int? {
        if (currentIndex < 0 || currentIndex >= episodeCount) return null
        val target = if (direction == Direction.NEXT) currentIndex + 1 else currentIndex - 1
        return target.takeIf { it in 0 until episodeCount }
    }
}

object VideoLibraryItemAccess : LibraryItemAccess<VideoLibraryEntry> {
    override fun key(item: VideoLibraryEntry): String = item.key
    override fun sourceKey(item: VideoLibraryEntry): String = item.sourceKey
}
