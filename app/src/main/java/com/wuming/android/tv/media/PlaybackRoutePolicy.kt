package com.wuming.android.tv.media

import com.wuming.android.tv.bean.Episode
import com.wuming.android.tv.bean.History
import com.wuming.android.tv.setting.AppSettings

/** Selects a playback identity without leaking one resolver's history into another route. */
object PlaybackRoutePolicy {
    @JvmStatic
    fun select(linkRoute: Int, history: History?, flag: String?, episode: Episode?): String {
        val current = episode?.getUrl() ?: ""
        return com.wuming.tv.core.media.PlaybackRouteSelection.select(linkRoute == AppSettings.LINK_ROUTE_DIRECT,
            current) { history?.getPlaybackId(flag, episode) }
    }
}
