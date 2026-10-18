package com.wuming.tv.desktop.library

import com.wuming.tv.core.catalog.CatalogItem
import com.wuming.tv.core.catalog.CatalogDetail
import com.wuming.tv.core.library.ContentKeyCodec
import com.wuming.tv.core.library.recommend.ViewingRecord
import com.google.gson.annotations.SerializedName
import java.util.UUID

/**
 * Desktop-side durable playback session, persisted as JSON. Implements the core
 * [ViewingRecord] contract so the shared [com.wuming.tv.core.library.recommend.RecommendationPolicy]
 * ranks desktop catalogue items using the exact same preference learning as mobile.
 */
data class DesktopViewingRecord(
    @SerializedName("id") val id: String = UUID.randomUUID().toString(),
    @SerializedName("key") override val key: String = "",
    @SerializedName("siteKey") val siteKey: String = "",
    @SerializedName("vodId") val vodId: String = "",
    @SerializedName("vodName") val vodName: String = "",
    @SerializedName("typeName") override val typeName: String = "",
    @SerializedName("year") override val year: String = "",
    @SerializedName("area") override val area: String = "",
    @SerializedName("director") override val director: String = "",
    @SerializedName("actor") override val actor: String = "",
    @SerializedName("content") override val content: String = "",
    @SerializedName("watchedDuration") override val watchedDurationMillis: Long = 0,
    @SerializedName("lastWatchedAt") override val lastWatchedAtMillis: Long = 0,
    @SerializedName("completed") override val completed: Boolean = false,
) : ViewingRecord {
    companion object {
        fun fromPlayback(
            item: CatalogItem,
            detail: CatalogDetail?,
            watchedDurationMillis: Long,
            lastWatchedAtMillis: Long,
            completed: Boolean,
        ): DesktopViewingRecord = DesktopViewingRecord(
            key = item.sourceKey + ContentKeyCodec.DEFAULT_DELIMITER + item.id,
            siteKey = item.sourceKey,
            vodId = item.id,
            vodName = item.name,
            typeName = item.typeName,
            year = item.year,
            area = detail?.area.orEmpty(),
            director = detail?.director.orEmpty(),
            actor = detail?.actor.orEmpty(),
            content = detail?.content.orEmpty(),
            watchedDurationMillis = watchedDurationMillis.coerceAtLeast(0L),
            lastWatchedAtMillis = lastWatchedAtMillis.coerceAtLeast(0L),
            completed = completed,
        )
    }
}
