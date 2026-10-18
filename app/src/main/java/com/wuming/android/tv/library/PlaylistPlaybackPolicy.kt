package com.wuming.android.tv.library

import com.wuming.android.tv.bean.Episode
import com.wuming.android.tv.bean.History

/** Applies an optional playlist subset before the normal player chooses its first episode. */
object PlaylistPlaybackPolicy {
    @JvmStatic
    fun prepareHistory(history: History?, selection: PlaylistEpisodeSelection?) {
        if (history == null) return
        val initial = selection?.initialEpisode() ?: return
        history.setVodFlag(initial.first)
        history.setVodRemarks(initial.second)
    }

    @JvmStatic
    fun filter(selection: PlaylistEpisodeSelection?, flag: String?, episodes: List<Episode>?): List<Episode> {
        val available: List<Episode> = episodes?.let { ArrayList(it) } ?: ArrayList()
        if (selection == null) return available
        return selection.filter(flag, available)
    }
}
