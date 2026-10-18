package com.wuming.android.tv.utils

import com.wuming.android.tv.App
import com.wuming.android.tv.bean.Episode
import com.wuming.android.tv.bean.Result
import com.wuming.android.tv.bean.Vod
import com.wuming.android.tv.db.AppDatabase
import com.wuming.android.tv.search.TitleNormalizer
import com.github.catvod.utils.Prefers
import com.google.gson.reflect.TypeToken
import java.util.Comparator

object VideoRankManager {

    private const val STORE_KEY = "video_rankings"
    private val STORE_TYPE = object : TypeToken<LinkedHashMap<String, Entry>>() {}.type
    private var entries: MutableMap<String, Entry>? = null

    @Synchronized
    @JvmStatic
    fun getRank(vod: Vod?): Int {
        if (vod == null || vod.isAction || vod.isFolder) return 0
        return getContentRank(contentKey(vod.siteKey, vod.id))
    }

    @Synchronized
    @JvmStatic
    fun getContentRank(contentKey: String): Int {
        val entry = getEntries()[qualityKey(contentKey)]
        return entry?.rank ?: 0
    }

    @Synchronized
    @JvmStatic
    fun getAll(): List<Entry> {
        val result = ArrayList<Entry>()
        for (entry in getEntries().values) {
            val entryKey = entry.key
            if (entryKey != null && entryKey.startsWith("quality:")) result.add(entry)
        }
        result.sortWith(
            Comparator.comparingInt<Entry> { it.rank }.reversed().thenComparing { it.getTitle() }
        )
        return result
    }

    @Synchronized
    @JvmStatic
    fun setContent(contentKey: String, displayTitle: String, rank: Int) {
        val key = qualityKey(contentKey)
        if (key.isEmpty()) return
        getEntries()[key] = Entry(key, displayTitle, rank)
        save()
        clearSearchCaches()
    }

    @Synchronized
    @JvmStatic
    fun getEpisodeRank(contentKey: String, episode: Episode): Int {
        val entry = getEntries()[episodeKey(contentKey, episode)]
        return entry?.rank ?: getContentRank(contentKey)
    }

    @Synchronized
    @JvmStatic
    fun setEpisode(contentKey: String, displayTitle: String, episode: Episode, rank: Int) {
        val key = episodeKey(contentKey, episode)
        if (key.isEmpty()) return
        val title = "$displayTitle / ${episode.getDesc()}${episode.getName()}"
        getEntries()[key] = Entry(key, title, rank)
        save()
    }

    @JvmStatic
    fun applyEpisodes(contentKey: String, items: List<Episode>?): List<Episode> {
        val visible = ArrayList<Episode>()
        if (items == null) return visible
        for (item in items) {
            if (getEpisodeRank(contentKey, item) != -1) visible.add(item)
        }
        visible.sortWith { first, second ->
            Integer.compare(
                getEpisodeRank(contentKey, second),
                getEpisodeRank(contentKey, first)
            )
        }
        return visible
    }

    @Synchronized
    @JvmStatic
    fun set(entry: Entry?, rank: Int) {
        if (entry == null || entry.key.isNullOrEmpty()) return
        getEntries()[entry.key!!] = Entry(entry.key!!, entry.title, rank)
        save()
        clearSearchCaches()
    }

    @Synchronized
    @JvmStatic
    fun remove(entry: Entry?) {
        if (entry == null) return
        getEntries().remove(entry.key)
        save()
        clearSearchCaches()
    }

    @Synchronized
    @JvmStatic
    fun clear() {
        getEntries().clear()
        save()
        clearSearchCaches()
    }

    @JvmStatic
    fun apply(siteKey: String, result: Result?): Result? {
        if (result == null || result.list.isEmpty()) return result
        val visible = apply(siteKey, result.list)
        result.list = visible
        return result
    }

    @JvmStatic
    fun apply(items: List<Vod>?): List<Vod> = apply("", items)

    @JvmStatic
    fun apply(siteKey: String, items: List<Vod>?): List<Vod> {
        val visible = ArrayList<Vod>()
        if (items == null) return visible
        for (item in items) {
            val resolvedSite = if (item.siteKey.isEmpty()) siteKey else item.siteKey
            if (getContentRank(contentKey(resolvedSite, item.id)) != -1) visible.add(item)
        }
        visible.sortWith { first, second ->
            val firstSite = if (first.siteKey.isEmpty()) siteKey else first.siteKey
            val secondSite = if (second.siteKey.isEmpty()) siteKey else second.siteKey
            Integer.compare(
                getContentRank(contentKey(secondSite, second.id)),
                getContentRank(contentKey(firstSite, first.id))
            )
        }
        return visible
    }

    @JvmStatic
    fun contentKey(siteKey: String?, videoId: String?): String {
        if (siteKey.isNullOrEmpty() || videoId.isNullOrEmpty()) return ""
        return siteKey + AppDatabase.SYMBOL + videoId
    }

    private fun qualityKey(contentKey: String?): String {
        return if (contentKey.isNullOrEmpty()) "" else "quality:$contentKey"
    }

    private fun episodeKey(contentKey: String?, episode: Episode?): String {
        if (episode == null) return ""
        val child = TitleNormalizer.key(episode.getDesc() + episode.getName())
        if (contentKey.isNullOrEmpty() || child.isEmpty()) return ""
        return "quality:$contentKey:episode:$child"
    }

    @Synchronized
    private fun getEntries(): MutableMap<String, Entry> {
        if (entries != null) return entries!!
        return try {
            val stored = App.gson().fromJson<LinkedHashMap<String, Entry>>(
                Prefers.getString(STORE_KEY, "{}"), STORE_TYPE
            )
            entries = if (stored == null) LinkedHashMap() else LinkedHashMap(stored)
            entries!!
        } catch (ignored: Exception) {
            entries = LinkedHashMap()
            entries!!
        }
    }

    @Synchronized
    private fun save() {
        Prefers.put(STORE_KEY, App.gson().toJson(getEntries()))
    }

    private fun clearSearchCaches() {
        SearchCache.get().clear()
        com.wuming.android.tv.browse.VodBrowse.clear()
    }

    class Entry {
        @JvmField
        var key: String? = null

        @JvmField
        var title: String? = null

        @JvmField
        var rank: Int = 0

        constructor()

        internal constructor(key: String, title: String?, rank: Int) {
            this.key = key
            this.title = title ?: ""
            this.rank = rank
        }

        fun getTitle(): String = title ?: ""

        fun getRank(): Int = rank
    }
}
