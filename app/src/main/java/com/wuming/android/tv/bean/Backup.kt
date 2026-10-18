package com.wuming.android.tv.bean

import com.wuming.android.tv.App
import com.wuming.android.tv.db.AppDatabase
import com.wuming.android.tv.media.catalog.MediaResourceEntity
import com.wuming.android.tv.media.catalog.MediaSourceEntity
import com.wuming.android.tv.recommendation.RecommendationEngine
import com.wuming.android.tv.server.RemoteAccess
import com.github.catvod.utils.Prefers
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import com.google.gson.annotations.SerializedName

class Backup {

    @SerializedName("site")
    private var site: List<Site>? = null

    @SerializedName("live")
    private var live: List<Live>? = null

    @SerializedName("keep")
    private var keep: List<Keep>? = null

    @SerializedName("config")
    private var config: List<Config>? = null

    @SerializedName("history")
    private var history: List<History>? = null

    @SerializedName("viewingRecord")
    private var viewingRecord: List<ViewingRecord>? = null

    @SerializedName("mediaResource")
    private var mediaResource: List<MediaResourceEntity>? = null

    @SerializedName("mediaSource")
    private var mediaSource: List<MediaSourceEntity>? = null

    @SerializedName("prefers")
    private var prefers: Map<String, Any?>? = null

    fun getSite(): List<Site> = BeanUtils.emptyListIfNull(site)
    fun setSite(site: List<Site>?) { this.site = site }

    fun getLive(): List<Live> = BeanUtils.emptyListIfNull(live)
    fun setLive(live: List<Live>?) { this.live = live }

    fun getKeep(): List<Keep> = BeanUtils.emptyListIfNull(keep)
    fun setKeep(keep: List<Keep>?) { this.keep = keep }

    fun getConfig(): List<Config> = BeanUtils.emptyListIfNull(config)
    fun setConfig(config: List<Config>?) { this.config = config }

    fun getHistory(): List<History> = BeanUtils.emptyListIfNull(history)
    fun setHistory(history: List<History>?) { this.history = history }

    fun getViewingRecord(): List<ViewingRecord> = BeanUtils.emptyListIfNull(viewingRecord)
    fun setViewingRecord(viewingRecord: List<ViewingRecord>?) { this.viewingRecord = viewingRecord }

    fun getMediaResource(): List<MediaResourceEntity> = BeanUtils.emptyListIfNull(mediaResource)
    fun setMediaResource(mediaResource: List<MediaResourceEntity>?) { this.mediaResource = mediaResource }

    fun getMediaSource(): List<MediaSourceEntity> = BeanUtils.emptyListIfNull(mediaSource)
    fun setMediaSource(mediaSource: List<MediaSourceEntity>?) { this.mediaSource = mediaSource }

    fun getPrefers(): Map<String, Any?> = BeanUtils.emptyMapIfNull(prefers)
    fun setPrefers(prefers: Map<String, Any?>?) { this.prefers = prefers }

    fun restore() {
        restore(AppDatabase.get())
    }

    fun restore(database: AppDatabase) {
        database.runInTransaction {
            // Only replace portable tables represented by this backup. Operational tables
            // (especially account-scoped cloud ownership/audit) must survive restore.
            val sql = database.openHelper.writableDatabase
            for (table in listOf("MediaSource", "MediaResource", "ViewingRecord", "History",
                "Keep", "Site", "Live", "Config")) sql.execSQL("DELETE FROM `$table`")
            database.getSiteDao().insertOrUpdate(getSite())
            database.getLiveDao().insertOrUpdate(getLive())
            database.getKeepDao().insertOrUpdate(getKeep())
            database.getConfigDao().insertOrUpdate(getConfig())
            database.getHistoryDao().insertOrUpdate(getHistory())
            database.getViewingRecordDao().insertOrUpdate(getViewingRecord())
            database.getMediaCatalogDao().insertResource(getMediaResource())
            database.getMediaCatalogDao().insertSources(getMediaSource())
        }
        RecommendationEngine.invalidate()
        for (entry in getPrefers().entries) {
            if (!RemoteAccess.isSecretPreference(entry.key)) Prefers.put(entry.key, entry.value)
        }
    }

    override fun toString(): String = App.gson().toJson(this)

    companion object {
        private val LAZY_NUMBER_GSON: Gson = GsonBuilder()
            .setObjectToNumberStrategy(ToNumberPolicy.LAZILY_PARSED_NUMBER)
            .create()

        @JvmStatic
        fun create(): Backup = create(AppDatabase.get())

        @JvmStatic
        fun create(database: AppDatabase): Backup {
            val backup = Backup()
            val preferences = HashMap(Prefers.getPrefers().all)
            preferences.keys.removeIf { RemoteAccess.isSecretPreference(it) }
            backup.setPrefers(preferences)
            backup.setSite(database.getSiteDao().findAll())
            backup.setLive(database.getLiveDao().findAll())
            backup.setKeep(database.getKeepDao().findAll())
            backup.setConfig(database.getConfigDao().findAll())
            backup.setHistory(database.getHistoryDao().findAll())
            backup.setViewingRecord(database.getViewingRecordDao().findRecent(Int.MAX_VALUE))
            backup.setMediaResource(database.getMediaCatalogDao().findAllResources())
            backup.setMediaSource(database.getMediaCatalogDao().findAllSources())
            return backup
        }

        @JvmStatic
        fun objectFrom(json: String): Backup {
            return try {
                LAZY_NUMBER_GSON.fromJson(json, Backup::class.java) ?: Backup()
            } catch (e: Exception) {
                Backup()
            }
        }
    }
}
