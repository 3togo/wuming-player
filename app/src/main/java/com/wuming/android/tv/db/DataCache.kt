package com.wuming.android.tv.db

import com.wuming.android.tv.bean.BeanUtils
import com.wuming.android.tv.bean.Config
import com.wuming.android.tv.bean.Device
import com.wuming.android.tv.bean.Drive
import com.wuming.android.tv.bean.History
import com.wuming.android.tv.bean.Keep
import com.wuming.android.tv.bean.Live
import com.wuming.android.tv.bean.Site
import com.wuming.android.tv.bean.SitePerformance
import com.wuming.android.tv.bean.Track
import com.wuming.android.tv.utils.Task
import com.wuming.tv.core.cache.MemoryBudget
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor

/**
 * In-memory snapshots of small database tables. Writes publish a new snapshot synchronously,
 * then persist asynchronously. Callers check isReady() or awaitReady() before accessing data.
 */
open class DataCache @JvmOverloads constructor(private val persistence: Executor = Executor { Task.execute(it) }) {
    private val activeConfigByType = HashMap<Int, Config?>(3)
    private val configByKey = ConcurrentHashMap<String, Config>()
    private val configByType = HashMap<Int, List<Config>>(3)

    @Volatile private var keeps: List<Keep> = Collections.emptyList()
    @Volatile private var histories: List<History> = Collections.emptyList()
    @Volatile private var devices: List<Device> = Collections.emptyList()
    @Volatile private var drives: List<Drive> = Collections.emptyList()
    @Volatile private var tracks: List<Track> = Collections.emptyList()
    @Volatile private var sites: List<Site> = Collections.emptyList()
    @Volatile private var lives: List<Live> = Collections.emptyList()
    @Volatile private var ready = false
    @Volatile private var initFailed = false
    private val readyLatch = CountDownLatch(1)
    private val performanceBySiteKey = ConcurrentHashMap<String, SitePerformance>()

    /** Loads all tables on a background thread, preserving the existing startup protocol. */
    open fun init() {
        try {
            val db = AppDatabase.get()
            Config.defaultsSync()
            for (config in db.getConfigDao().findAll()) configByKey[configKey(config.url, config.type)] = config
            for (type in 0..2) {
                configByType[type] = ArrayList(db.getConfigDao().findByType(type))
                val active = db.getConfigDao().findOne(type)
                if (active != null) activeConfigByType[type] = active
            }
            val latch = CountDownLatch(7)
            Task.largeExecutor().execute { keeps = ArrayList(db.getKeepDao().findAll()); latch.countDown() }
            Task.largeExecutor().execute { histories = ArrayList(db.getHistoryDao().findRecentLimit(HISTORY_MAX_IN_MEMORY)); latch.countDown() }
            Task.largeExecutor().execute { devices = ArrayList(db.getDeviceDao().findAll()); latch.countDown() }
            Task.largeExecutor().execute { drives = ArrayList(db.getDriveDao().getAll()); latch.countDown() }
            Task.largeExecutor().execute { sites = ArrayList(db.getSiteDao().findAll()); latch.countDown() }
            Task.largeExecutor().execute { lives = ArrayList(db.getLiveDao().findAll()); latch.countDown() }
            Task.largeExecutor().execute {
                performanceBySiteKey.clear()
                for (performance in db.getSitePerformanceDao().findAll()) putSitePerformance(performance)
                latch.countDown()
            }
            tracks = ArrayList()
            latch.await()
            ready = true
            readyLatch.countDown()
        } catch (error: Exception) {
            android.util.Log.e("DataCache", "Failed to initialize cache", error)
            initFailed = true
            readyLatch.countDown()
        }
    }

    open fun getActiveConfig(type: Int): Config? = activeConfigByType[type]
    open fun setActiveConfig(type: Int, config: Config?) { activeConfigByType[type] = config }
    open fun getConfigsByType(type: Int): List<Config> = configByType[type]?.let { Collections.unmodifiableList(it) } ?: Collections.emptyList()
    open fun findConfig(url: String?, type: Int): Config? = if (BeanUtils.isEmpty(url)) null else configByKey[configKey(url, type)]
    open fun findConfigById(id: Int): Config? = configByKey.values.firstOrNull { it.id == id }
    open fun getVodConfigUrls(): List<Config> = configByType[0]?.let { Collections.unmodifiableList(it) } ?: Collections.emptyList()

    @Synchronized
    open fun putConfig(config: Config?) {
        if (config == null || BeanUtils.isEmpty(config.url)) return
        configByKey[configKey(config.url, config.type)] = config
        val type = config.type
        configByType[type] = changed(configByType[type] ?: Collections.emptyList()) { copy ->
            val index = copy.indexOfFirst { it.url.equals(config.url) }
            if (index >= 0) copy[index] = config else copy.add(config)
        }
        persistence.execute { AppDatabase.get().getConfigDao().insertOrUpdate(config) }
    }

    @Synchronized
    open fun removeConfig(url: String, type: Int) {
        configByKey.remove(configKey(url, type))
        configByType[type]?.let { typed -> configByType[type] = changed(typed) { copy ->
            copy.removeIf { url == it.url && it.type == type }
        } }
        persistence.execute { AppDatabase.get().getConfigDao().delete(url, type) }
    }

    @Synchronized
    open fun removeConfig(url: String) {
        configByKey.entries.removeIf { url == it.value.url }
        for ((type, typed) in HashMap(configByType)) configByType[type] = changed(typed) { copy -> copy.removeIf { url == it.url } }
        persistence.execute { AppDatabase.get().getConfigDao().delete(url) }
    }

    open fun getKeeps(): List<Keep> = Collections.unmodifiableList(keeps)
    open fun getVodKeeps(): List<Keep> = keeps.filterTo(ArrayList()) { it.type == 0 }
    open fun getLiveKeeps(): List<Keep> = keeps.filterTo(ArrayList()) { it.type == 1 }
    open fun findKeep(cid: Int, key: String): Keep? = keeps.firstOrNull { it.cid == cid && key == it.key }
    open fun findKeep(key: String): Keep? = keeps.firstOrNull { key == it.key }
    open fun keepExists(key: String): Boolean = keeps.any { key == it.key }

    @Synchronized
    open fun putKeep(keep: Keep) {
        keeps = changed(keeps) { copy -> copy.removeIf { it.key == keep.key }; copy.add(keep) }
        persistence.execute { AppDatabase.get().getKeepDao().insertOrUpdate(keep) }
    }

    open fun removeKeep(key: String) { removeLiveKeep(key) }

    @Synchronized
    open fun removeLiveKeep(key: String) {
        keeps = changed(keeps) { copy -> copy.removeIf { it.type == 1 && it.key == key } }
        persistence.execute { AppDatabase.get().getKeepDao().delete(key) }
    }

    @Synchronized
    open fun removeVideoKeep(cid: Int, key: String) {
        keeps = changed(keeps) { copy -> copy.removeIf { it.type == 0 && it.cid == cid && it.key == key } }
        persistence.execute { AppDatabase.get().getKeepDao().delete(cid, key) }
    }

    open fun removeKeepsByCid(cid: Int) { removeVideoKeepsByCid(cid) }

    @Synchronized
    open fun removeVideoKeepsByCid(cid: Int) {
        keeps = changed(keeps) { copy -> copy.removeIf { it.type == 0 && it.cid == cid } }
        persistence.execute { AppDatabase.get().getKeepDao().delete(cid) }
    }

    open fun clearKeeps() { clearVideoKeeps() }

    @Synchronized
    open fun clearVideoKeeps() {
        keeps = changed(keeps) { copy -> copy.removeIf { it.type == 0 } }
        persistence.execute { AppDatabase.get().getKeepDao().delete() }
    }

    open fun getHistories(): List<History> = Collections.unmodifiableList(histories)
    open fun findRecentHistories(cutoffTime: Long): List<History> = histories.filterTo(ArrayList()) { it.getCreateTime() >= cutoffTime }
    open fun findHistoriesByCid(cid: Int, cutoffTime: Long): List<History> =
        histories.filterTo(ArrayList()) { it.getCid() == cid && it.getCreateTime() >= cutoffTime }
    open fun findHistory(key: String): History? = histories.firstOrNull { key == it.getKey() }
    open fun findHistoryByName(name: String?): List<History> =
        if (name == null) ArrayList() else histories.filterTo(ArrayList()) { name == it.getVodName() }

    @Synchronized
    open fun putHistory(history: History) {
        val key = history.getKey()
        histories = changed(histories) { copy -> copy.removeIf { matchesHistoryKey(key, it.getKey()) }; copy.add(history) }
        persistence.execute { AppDatabase.get().getHistoryDao().insertOrUpdate(history) }
    }

    @Synchronized
    open fun removeHistory(key: String?) {
        if (key == null) return
        histories = changed(histories) { copy -> copy.removeIf { key == it.getKey() } }
        persistence.execute {
            AppDatabase.get().getHistoryDao().delete(com.wuming.android.tv.api.config.VodConfig.getCid(), key)
            AppDatabase.get().getTrackDao().delete(key)
        }
    }

    @Synchronized
    open fun clearHistoriesByCid(cid: Int) {
        histories = changed(histories) { copy -> copy.removeIf { it.getCid() == cid } }
        persistence.execute { AppDatabase.get().getHistoryDao().delete(cid) }
    }

    @Synchronized
    open fun trimHistories(maxItems: Int) {
        if (histories.size <= maxItems) return
        val copy = ArrayList(histories)
        copy.sortWith { a, b -> java.lang.Long.compare(b.getCreateTime(), a.getCreateTime()) }
        histories = Collections.unmodifiableList(ArrayList(copy.subList(0, minOf(maxItems, copy.size))))
    }

    open fun getDevices(): List<Device> = Collections.unmodifiableList(devices)

    @Synchronized
    open fun putDevice(device: Device) {
        devices = changed(devices) { copy -> copy.removeIf { it.getUuid().equals(device.getUuid()) }; copy.add(device) }
        persistence.execute { AppDatabase.get().getDeviceDao().insertOrUpdate(device) }
    }

    @Synchronized
    open fun clearDevices() {
        devices = Collections.emptyList()
        persistence.execute { AppDatabase.get().getDeviceDao().delete() }
    }

    open fun getDrives(): List<Drive> = Collections.unmodifiableList(drives)

    @Synchronized
    open fun putDrive(drive: Drive) {
        drives = changed(drives) { copy -> copy.removeIf { it.id == drive.id }; copy.add(drive) }
        persistence.execute { AppDatabase.get().getDriveDao().insert(drive) }
    }

    @Synchronized
    open fun updateDrive(drive: Drive) {
        drives = changed(drives) { copy ->
            val index = copy.indexOfFirst { it.id == drive.id }
            if (index >= 0) copy[index] = drive
        }
        persistence.execute { AppDatabase.get().getDriveDao().update(drive) }
    }

    @Synchronized
    open fun removeDrive(id: Int) {
        drives = changed(drives) { copy -> copy.removeIf { it.id == id } }
        persistence.execute { AppDatabase.get().getDriveDao().delete(id) }
    }

    open fun findTracks(key: String?): List<Track> =
        if (BeanUtils.isEmpty(key)) Collections.emptyList() else tracks.filterTo(ArrayList()) { key == it.key }

    @Synchronized
    open fun putTrack(track: Track) {
        if (BeanUtils.isEmpty(track.key)) return
        tracks = changed(tracks) { copy -> copy.removeIf { it.key!!.equals(track.key) && it.type == track.type }; copy.add(track) }
        persistence.execute { AppDatabase.get().getTrackDao().insert(track) }
    }

    @Synchronized
    open fun removeTracks(key: String?) {
        if (BeanUtils.isEmpty(key)) return
        tracks = changed(tracks) { copy -> copy.removeIf { key == it.key } }
        persistence.execute { AppDatabase.get().getTrackDao().delete(key) }
    }

    open fun getSites(): List<Site> = Collections.unmodifiableList(sites)

    @Synchronized
    open fun putSite(site: Site) {
        sites = changed(sites) { copy -> copy.removeIf { it.key.equals(site.key) }; copy.add(site) }
        persistence.execute { AppDatabase.get().getSiteDao().insertOrUpdate(site) }
    }

    open fun getLives(): List<Live> = Collections.unmodifiableList(lives)

    @Synchronized
    open fun putLive(live: Live) {
        lives = changed(lives) { copy -> copy.removeIf { it.getName().equals(live.getName()) }; copy.add(live) }
        persistence.execute { AppDatabase.get().getLiveDao().insertOrUpdate(live) }
    }

    open fun getSitePerformance(siteKey: String): SitePerformance? = performanceBySiteKey[siteKey]
    open fun getAllSitePerformance(): List<SitePerformance> = ArrayList(performanceBySiteKey.values)

    open fun putSitePerformance(perf: SitePerformance?) {
        val key = perf?.siteKey ?: return
        if (performanceBySiteKey.size >= MAX_PERFORMANCE_ENTRIES) performanceBySiteKey.clear()
        performanceBySiteKey[key] = perf
    }

    open fun removeSitePerformance(siteKey: String?) { if (siteKey != null) performanceBySiteKey.remove(siteKey) }
    open fun clearSitePerformance() { performanceBySiteKey.clear() }
    open fun reload() { init() }

    companion object {
        @Volatile private var instance: DataCache? = null
        private val HISTORY_MAX_IN_MEMORY = MemoryBudget.current().scale(200, 500, 1000)
        private const val MAX_PERFORMANCE_ENTRIES = 512

        /** Never mutate a published list; readers may still be iterating an older snapshot. */
        private fun <T> changed(previous: List<T>, mutation: (MutableList<T>) -> Unit): List<T> {
            val copy = ArrayList(previous)
            mutation(copy)
            return Collections.unmodifiableList(copy)
        }

        private fun matchesHistoryKey(key: String?, candidate: String?): Boolean = key != null && key == candidate

        private fun configKey(url: String?, type: Int): String = "$type:$url"

        @JvmStatic
        fun get(): DataCache {
            if (instance == null) synchronized(DataCache::class.java) { if (instance == null) instance = DataCache() }
            return instance!!
        }

        @JvmStatic fun isReady(): Boolean = get().ready
        @JvmStatic fun isInitFailed(): Boolean = get().initFailed

        /** Blocks only when a caller absolutely needs cache data before proceeding. */
        @JvmStatic
        fun awaitReady() {
            try { get().readyLatch.await() }
            catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
    }
}
