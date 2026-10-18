package com.wuming.tv.desktop.live

import com.wuming.tv.core.live.LiveLibraryStore
import com.wuming.tv.core.live.LiveSelection
import java.util.prefs.Preferences

class DesktopLiveLibraryStore(
    private val preferences: Preferences = Preferences.userRoot().node("com/wuming/tv/desktop/live-library"),
) : LiveLibraryStore {
    override fun favorite(source: String, channel: String) = preferences.node(source).getBoolean(channel, false)
    override fun setFavorite(source: String, channel: String, favorite: Boolean) {
        val node = preferences.node(source)
        if (favorite) node.putBoolean(channel, true) else node.remove(channel)
        node.flush()
    }
    override fun selection(source: String): LiveSelection? {
        val node = preferences.node(source)
        val channel = node.get("last-channel", null) ?: return null
        return LiveSelection(channel, node.get("last-route", ""))
    }
    override fun saveSelection(source: String, selection: LiveSelection) {
        preferences.node(source).apply {
            put("last-channel", selection.channel)
            put("last-route", selection.route)
            flush()
        }
    }
}
