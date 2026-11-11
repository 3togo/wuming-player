package com.wuming.tv.core.library

/** A window in the displayed order, independent of reverse-play direction or episode numbering. */
data class EpisodeWindow(val start: Int, val endExclusive: Int, val selected: Int) {
    fun absoluteIndex(localIndex: Int): Int? =
        if (localIndex in 0 until endExclusive - start) start + localIndex else null

    companion object {
        @JvmStatic
        @JvmOverloads
        fun around(count: Int, current: Int, radius: Int = 60): EpisodeWindow {
            require(count >= 0 && radius >= 0)
            if (count == 0) return EpisodeWindow(0, 0, -1)
            val selected = current.coerceIn(0, count - 1)
            val start = (selected.toLong() - radius).coerceAtLeast(0).toInt()
            val end = (selected.toLong() + radius + 1).coerceAtMost(count.toLong()).toInt()
            return EpisodeWindow(start, end, selected - start)
        }
    }
}
