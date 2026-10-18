package com.wuming.android.tv.bean

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import com.wuming.tv.core.library.ViewingProgressPolicy
import com.google.gson.annotations.SerializedName
import java.util.UUID

/** One durable playback session; records are never merged by title. */
@Entity(indices = [Index(value = ["key"]), Index(value = ["siteKey"]), Index(value = ["lastWatchedAt"])])
open class ViewingRecord : com.wuming.tv.core.library.recommend.ViewingRecord {
    @field:PrimaryKey
    open var id: String = UUID.randomUUID().toString()

    // Room needs nullable storage while the core interface exposes normalized text.
    // The storage accessors delegate to the legacy accessors, including Java overrides.
    @field:ColumnInfo(name = "key")
    @field:SerializedName("key")
    private var storedKey: String? = null

    fun getStoredKey(): String? = key

    fun setStoredKey(value: String?) {
        setKey(value)
    }

    @get:Ignore
    override val key: String get() = storedKey.orEmpty()

    open fun setKey(value: String?) {
        storedKey = value
    }

    private var siteKey: String? = null

    open fun getSiteKey(): String? = siteKey.orEmpty()

    open fun setSiteKey(value: String?) {
        siteKey = value
    }

    private var vodId: String? = null

    open fun getVodId(): String? = vodId.orEmpty()

    open fun setVodId(value: String?) {
        vodId = value
    }

    private var vodName: String? = null

    open fun getVodName(): String? = vodName.orEmpty()

    open fun setVodName(value: String?) {
        vodName = value
    }

    private var vodPic: String? = null

    open fun getVodPic(): String? = vodPic.orEmpty()

    open fun setVodPic(value: String?) {
        vodPic = value
    }

    private var episode: String? = null

    open fun getEpisode(): String? = episode.orEmpty()

    open fun setEpisode(value: String?) {
        episode = value
    }

    @field:ColumnInfo(name = "typeName")
    @field:SerializedName("typeName")
    private var storedTypeName: String? = null

    fun getStoredTypeName(): String? = typeName

    fun setStoredTypeName(value: String?) {
        setTypeName(value)
    }

    @get:Ignore
    override val typeName: String get() = storedTypeName.orEmpty()

    open fun setTypeName(value: String?) {
        storedTypeName = value
    }

    @field:ColumnInfo(name = "year")
    @field:SerializedName("year")
    private var storedYear: String? = null

    fun getStoredYear(): String? = year

    fun setStoredYear(value: String?) {
        setYear(value)
    }

    @get:Ignore
    override val year: String get() = storedYear.orEmpty()

    open fun setYear(value: String?) {
        storedYear = value
    }

    @field:ColumnInfo(name = "area")
    @field:SerializedName("area")
    private var storedArea: String? = null

    fun getStoredArea(): String? = area

    fun setStoredArea(value: String?) {
        setArea(value)
    }

    @get:Ignore
    override val area: String get() = storedArea.orEmpty()

    open fun setArea(value: String?) {
        storedArea = value
    }

    @field:ColumnInfo(name = "director")
    @field:SerializedName("director")
    private var storedDirector: String? = null

    fun getStoredDirector(): String? = director

    fun setStoredDirector(value: String?) {
        setDirector(value)
    }

    @get:Ignore
    override val director: String get() = storedDirector.orEmpty()

    open fun setDirector(value: String?) {
        storedDirector = value
    }

    @field:ColumnInfo(name = "actor")
    @field:SerializedName("actor")
    private var storedActor: String? = null

    fun getStoredActor(): String? = actor

    fun setStoredActor(value: String?) {
        setActor(value)
    }

    @get:Ignore
    override val actor: String get() = storedActor.orEmpty()

    open fun setActor(value: String?) {
        storedActor = value
    }

    @field:ColumnInfo(name = "content")
    @field:SerializedName("content")
    private var storedContent: String? = null

    fun getStoredContent(): String? = content

    fun setStoredContent(value: String?) {
        setContent(value)
    }

    @get:Ignore
    override val content: String get() = storedContent.orEmpty()

    open fun setContent(value: String?) {
        storedContent = value
    }

    open var startedAt: Long = 0
    open var endedAt: Long = 0
    open var lastWatchedAt: Long = 0
    open var watchedDuration: Long = 0
    open var mediaDuration: Long = 0
    open var startPosition: Long = 0
    open var endPosition: Long = 0
    override var completed: Boolean = false
    open var cid: Int = 0

    @get:Ignore
    override val watchedDurationMillis: Long get() = watchedDuration

    @get:Ignore
    override val lastWatchedAtMillis: Long get() = lastWatchedAt

    open fun snapshot(): ViewingRecord {
        val copy = ViewingRecord()
        copy.id = id
        copy.storedKey = storedKey
        copy.siteKey = siteKey
        copy.vodId = vodId
        copy.vodName = vodName
        copy.vodPic = vodPic
        copy.episode = episode
        copy.storedTypeName = storedTypeName
        copy.storedYear = storedYear
        copy.storedArea = storedArea
        copy.storedDirector = storedDirector
        copy.storedActor = storedActor
        copy.storedContent = storedContent
        copy.startedAt = startedAt
        copy.endedAt = endedAt
        copy.lastWatchedAt = lastWatchedAt
        copy.watchedDuration = watchedDuration
        copy.mediaDuration = mediaDuration
        copy.startPosition = startPosition
        copy.endPosition = endPosition
        copy.completed = completed
        copy.cid = cid
        return copy
    }

    open fun update(
        watchedDelta: Long,
        now: Long,
        position: Long,
        duration: Long,
    ) {
        val progress = ViewingProgressPolicy.update(watchedDuration, watchedDelta, now, position, mediaDuration, duration, completed)
        watchedDuration = progress.watchedDurationMillis
        lastWatchedAt = progress.lastWatchedAtMillis
        endPosition = progress.endPositionMillis
        mediaDuration = progress.mediaDurationMillis
        completed = progress.completed
    }

    open fun finish(
        now: Long,
        ended: Boolean,
    ) {
        endedAt = now
        lastWatchedAt = now
        completed = completed || ended
    }

    open fun matches(history: History?): Boolean =
        history != null &&
            ViewingProgressPolicy.matches(storedKey, episode, history.key, history.vodRemarks)

    companion object {
        @JvmStatic fun create(
            history: History,
            vod: Vod?,
            now: Long,
            position: Long,
            duration: Long,
        ): ViewingRecord {
            val item = ViewingRecord()
            item.storedKey = history.key
            item.siteKey = history.siteKey
            item.vodId = history.vodId
            item.vodName = history.vodName
            item.vodPic = history.vodPic
            item.episode = history.vodRemarks
            item.cid = history.cid
            item.startedAt = now
            item.lastWatchedAt = now
            item.startPosition = maxOf(0L, position)
            item.endPosition = maxOf(0L, position)
            item.mediaDuration = maxOf(0L, duration)
            if (vod != null) {
                item.storedTypeName = vod.typeName
                item.storedYear = vod.year
                item.storedArea = vod.area
                item.storedDirector = vod.director
                item.storedActor = vod.actor
                item.storedContent = vod.content
                if (item.vodPic.isNullOrEmpty()) item.vodPic = vod.pic
            }
            return item
        }
    }
}
