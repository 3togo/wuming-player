package com.wuming.android.tv.library

import com.wuming.android.tv.Constant
import com.wuming.android.tv.api.config.VodConfig
import com.wuming.android.tv.bean.Config
import com.wuming.android.tv.bean.Episode
import com.wuming.android.tv.bean.Flag
import com.wuming.android.tv.bean.History
import com.wuming.android.tv.bean.Keep
import com.wuming.android.tv.bean.Site
import com.wuming.android.tv.db.AsyncDB
import com.wuming.android.tv.repository.AndroidFavoriteRepository
import com.wuming.android.tv.repository.AndroidHistoryRepository
import com.wuming.android.tv.utils.VideoRankManager
import com.wuming.tv.core.library.ContentRanker
import com.wuming.tv.core.library.FavoriteRepository
import com.wuming.tv.core.library.HistoryPolicy
import com.wuming.tv.core.library.HistoryRepository
import com.wuming.tv.core.library.LibraryItemAccess
import com.wuming.tv.core.library.UserLibraryPolicy
import com.wuming.tv.core.library.recommend.FavoriteSignal
import java.lang.ref.WeakReference
import java.util.function.Consumer
import java.util.function.IntSupplier
import java.util.function.LongSupplier
import java.util.function.Supplier

/**
 * Application service for the user's history and favorites. Entities contain data and pure
 * behavior; repositories contain Android persistence; this class owns workflow orchestration.
 */
class UserLibrary(
    private val histories: HistoryRepository<History>,
    private val favorites: FavoriteRepository<Keep>,
    private val currentCatalogId: IntSupplier,
    private val availableSites: Supplier<List<Site>>,
    private val ranker: ContentRanker,
    private val clock: LongSupplier,
) {
    private val historyPolicy: UserLibraryPolicy<History> = UserLibraryPolicy(object : LibraryItemAccess<History> {
        override fun key(item: History): String = item.key
        override fun sourceKey(item: History): String = item.siteKey
    })
    private val favoritePolicy: UserLibraryPolicy<Keep> = UserLibraryPolicy(object : LibraryItemAccess<Keep> {
        override fun key(item: Keep): String = item.key
        override fun sourceKey(item: Keep): String = item.siteKey
    })

    fun histories(): List<History> {
        val cutoff = clock.asLong - Constant.HISTORY_TIME
        val items = histories.recent(cutoff)
        val sites = availableSites.get()
        val available = sites.map { it.key }.toSet()
        return historyPolicy.visibleAndRanked(items, available, ranker)
            .sortedByDescending { it.createTime }
    }

    fun histories(catalogId: Int): List<History> =
        histories.recent(catalogId, clock.asLong - Constant.HISTORY_TIME)

    fun findHistory(key: String): History? = histories.findByKey(key)

    fun findHistoryByName(name: String): List<History> = histories.findByName(name)

    fun deleteHistoryCatalog(catalogId: Int) {
        histories.deleteCatalog(catalogId)
    }

    fun mergeAndSave(target: History): History {
        merge(target, false, findHistoryByName(target.vodName))
        return saveHistory(target)
    }

    fun saveHistory(target: History): History {
        target.setUpdateTime(clock.asLong)
        histories.save(target)
        return target
    }

    fun saveHistory(target: History, catalogId: Int): History {
        target.cid = catalogId
        merge(target, true, findHistoryByName(target.vodName))
        return saveHistory(target)
    }

    fun saveHistoryAsync(target: History) {
        AsyncDB.execute { saveHistory(target) }
    }

    fun deleteHistory(target: History): History {
        histories.delete(currentCatalogId.asInt, target.key)
        return target
    }

    fun deleteHistoryAsync(target: History) {
        AsyncDB.execute { deleteHistory(target) }
    }

    fun replaceHistoryKey(target: History, key: String) {
        deleteHistory(target)
        target.setKey(key)
    }

    fun findEpisode(target: History, flags: List<Flag>) {
        if (flags.isEmpty()) return
        target.setVodFlag(flags[0].getFlag())
        if (flags[0].getEpisodes().isNotEmpty()) {
            target.setVodRemarks(flags[0].getEpisodes()[0].getName())
        }
        for (item in findHistoryByName(target.vodName)) {
            if (target.position > 0) break
            for (flag in flags) {
                val episode = flag.find(item.vodRemarks, true) ?: continue
                copyPreferences(item, target)
                target.setVodFlag(flag.getFlag())
                target.setPosition(item.position)
                target.setVodRemarks(episode.getName())
                break
            }
        }
    }

    fun syncHistories(targets: List<History>) {
        targets.forEach { target ->
            val items = findHistoryByName(target.vodName)
            if (items.isEmpty()) saveHistory(target, currentCatalogId.asInt)
            else {
                val latest = items.maxOfOrNull { it.createTime } ?: 0L
                if (target.createTime > latest) {
                    target.cid = currentCatalogId.asInt
                    merge(target, true, items)
                    saveHistory(target)
                }
            }
        }
    }

    fun findFavorite(key: String): Keep? = findFavorite(currentCatalogId.asInt, key)

    fun findFavorite(catalogId: Int, key: String): Keep? = favorites.find(catalogId, key)

    fun favoriteExists(key: String): Boolean = favorites.exists(key)

    fun videoFavorites(): List<Keep> = favoritePolicy.visibleAndRanked(favorites.videoFavorites(), emptySet(), ranker)

    fun liveFavorites(): List<Keep> = favorites.liveFavorites()

    fun saveFavorite(target: Keep) {
        favorites.save(target)
    }

    fun saveFavorite(target: Keep, catalogId: Int) {
        target.cid = catalogId
        saveFavorite(target)
    }

    fun saveFavoriteAsync(target: Keep) {
        AsyncDB.execute { saveFavorite(target) }
    }

    fun deleteFavorite(target: Keep): Keep {
        favorites.deleteVideo(target.cid, target.key)
        return target
    }

    fun deleteFavoriteAsync(target: Keep) {
        AsyncDB.execute { deleteFavorite(target) }
    }

    fun deleteLiveFavorite(key: String) {
        favorites.deleteLive(key)
    }

    fun deleteVideoCatalog(catalogId: Int) {
        favorites.deleteVideoCatalog(catalogId)
    }

    fun deleteAllVideoFavorites() {
        favorites.deleteAllVideos()
    }

    fun syncFavorites(configs: List<Config>, targets: List<Keep>) {
        targets.forEach { target ->
            configs.firstOrNull { target.cid == it.id }
                ?.let { config -> saveFavorite(target, Config.find(config).id) }
        }
    }

    fun historiesAsync(callback: Consumer<List<History>>) {
        AsyncDB.query({ histories() }, callback)
    }

    fun historiesAsync(callbackRef: WeakReference<Consumer<List<History>>>) {
        AsyncDB.query({ histories() }) { result ->
            callbackRef.get()?.accept(result)
        }
    }

    private fun merge(target: History, force: Boolean, existing: List<History>) {
        for (item in existing) {
            if (!HistoryPolicy.shouldMerge(item.key, item.duration, target.key, target.duration, force)) continue
            copyPreferences(item, target)
            histories.delete(currentCatalogId.asInt, item.key)
        }
    }

    companion object {
        @JvmStatic
        fun get(): UserLibrary = INSTANCE

        private val INSTANCE = UserLibrary(
            AndroidHistoryRepository.getInstance(),
            AndroidFavoriteRepository.getInstance(),
            IntSupplier { VodConfig.getCid() },
            Supplier { VodConfig.get().sites },
            ContentRanker { key -> VideoRankManager.getContentRank(key) },
            LongSupplier { System.currentTimeMillis() },
        )

        private fun copyPreferences(source: History, target: History) {
            if (source.opening > 0) target.setOpening(source.opening)
            if (source.ending > 0) target.setEnding(source.ending)
            if (source.speed != 1f) target.setSpeed(source.speed)
        }
    }

    fun getFavoriteSignals(): List<FavoriteSignal> = videoFavorites().map { KeepFavoriteSignal(it) }

    private class KeepFavoriteSignal(private val keep: Keep) : FavoriteSignal {
        override val key: String get() = keep.key
        override val typeName: String get() = ""
        override val year: String get() = ""
        override val area: String get() = ""
        override val director: String get() = ""
        override val actor: String get() = ""
        override val content: String get() = keep.vodName ?: ""
    }
}
