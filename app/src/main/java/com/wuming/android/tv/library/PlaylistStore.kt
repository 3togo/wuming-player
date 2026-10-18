package com.wuming.android.tv.library

import android.content.Context
import com.wuming.android.tv.App
import com.wuming.android.tv.bean.Episode
import com.wuming.android.tv.bean.History
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type
import java.util.LinkedHashMap

/** Persistent ordered playlist of VOD references. */
object PlaylistStore {
    private const val PREFS = "playlist"
    private const val ITEMS = "items"
    private const val ACTIVE = "active"
    private const val LOOP = "loop"
    private const val CURRENT = "current"
    private const val EPISODES = "episodes"

    @JvmStatic
    fun get(): PlaylistStore = this

    private val selectionType: Type = object : TypeToken<LinkedHashMap<String, PlaylistEpisodeSelection>>() {}.type

    private fun prefs() = App.get().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun items(): MutableList<History> = ArrayList(History.arrayFrom(prefs().getString(ITEMS, "[]")))

    @Synchronized
    fun add(item: History): Boolean {
        val items = items()
        for (existing in items) if (existing.key == item.key) return false
        items.add(History.objectFrom(App.gson().toJson(item)))
        save(items)
        return true
    }

    @Synchronized
    fun remove(item: History) {
        val items = items()
        items.removeIf { existing -> existing.key == item.key }
        save(items)
        removeSelection(item)
        if (item.key == prefs().getString(CURRENT, "")) pause()
    }

    @Synchronized
    fun move(item: History, offset: Int): Boolean {
        val items = items()
        var from = -1
        for (i in items.indices) {
            if (items[i].key == item.key) { from = i; break }
        }
        val to = from + offset
        if (from < 0 || to < 0 || to >= items.size) return false
        items.add(to, items.removeAt(from))
        save(items)
        return true
    }

    @Synchronized
    fun clear() {
        prefs().edit().remove(ITEMS).remove(ACTIVE).remove(CURRENT).remove(EPISODES).apply()
    }

    @Synchronized
    fun start(item: History) {
        prefs().edit().putBoolean(ACTIVE, true).putString(CURRENT, item.key).apply()
    }

    @Synchronized
    fun pause() {
        prefs().edit().putBoolean(ACTIVE, false).apply()
    }

    @Synchronized
    fun isActive(): Boolean = prefs().getBoolean(ACTIVE, false)

    @Synchronized
    fun isLoop(): Boolean = prefs().getBoolean(LOOP, false)

    @Synchronized
    fun toggleLoop(): Boolean {
        val enabled = !isLoop()
        prefs().edit().putBoolean(LOOP, enabled).apply()
        return enabled
    }

    @Synchronized
    fun advance(current: History?, offset: Int): History? {
        if (!isActive()) return null
        val items = items()
        var from = -1
        val key = current?.key ?: prefs().getString(CURRENT, "")
        for (i in items.indices) {
            if (items[i].key == key) { from = i; break }
        }
        if (from < 0 || items.isEmpty()) return null
        var target = from + offset
        if (target < 0 || target >= items.size) {
            if (!isLoop()) { pause(); return null }
            target = if (target < 0) items.size - 1 else 0
        }
        val next = items[target]
        prefs().edit().putString(CURRENT, next.key).apply()
        return next
    }

    @Synchronized
    fun selection(item: History?): PlaylistEpisodeSelection? = item?.let { selection(it.key) }

    @Synchronized
    fun selection(key: String?): PlaylistEpisodeSelection? {
        if (key.isNullOrEmpty()) return null
        return selections()[key]
    }

    @Synchronized
    fun usesAllEpisodes(item: History?): Boolean = selection(item) == null

    @Synchronized
    fun selectedEpisodeCount(item: History?): Int = selection(item)?.size() ?: 0

    @Synchronized
    fun useAllEpisodes(item: History?) {
        removeSelection(item)
    }

    @Synchronized
    fun saveEpisodeSelection(item: History?, flag: String?, selected: List<Episode>?, total: Int) {
        if (item == null) return
        if (selected == null || selected.size >= total) {
            useAllEpisodes(item)
            return
        }
        val names = mutableListOf<String>()
        val urls = mutableListOf<String>()
        for (episode in selected) {
            val name = episode.getName().trim()
            if (name.isNotEmpty() && name !in names) {
                names.add(name)
                urls.add(episode.getUrl())
            }
        }
        if (names.isEmpty()) return
        val selections = selections()
        selections[item.key] = PlaylistEpisodeSelection(flag, names, urls)
        saveSelections(selections)
    }

    @Synchronized
    fun filterEpisodes(item: History?, flag: String?, episodes: List<Episode>?): List<Episode> =
        PlaylistPlaybackPolicy.filter(selection(item), flag, episodes)

    private fun removeSelection(item: History?) {
        if (item == null) return
        val selections = selections()
        if (selections.remove(item.key) != null) saveSelections(selections)
    }

    private fun selections(): MutableMap<String, PlaylistEpisodeSelection> {
        val value = App.gson().fromJson<MutableMap<String, PlaylistEpisodeSelection>>(
            prefs().getString(EPISODES, "{}"), selectionType
        )
        return value?.let { LinkedHashMap(it) } ?: LinkedHashMap()
    }

    private fun saveSelections(selections: Map<String, PlaylistEpisodeSelection>) {
        prefs().edit().putString(EPISODES, App.gson().toJson(selections)).apply()
    }

    private fun save(items: List<History>) {
        prefs().edit().putString(ITEMS, App.gson().toJson(items)).apply()
    }
}
