package com.wuming.android.tv.browse

import android.net.Uri
import android.os.Bundle

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

import com.wuming.android.tv.bean.BeanUtils
import com.wuming.android.tv.bean.Result
import com.google.common.collect.ImmutableList

import com.wuming.tv.core.cache.ExpiringLruCache
import java.util.concurrent.TimeUnit

class BrowseTree {
    companion object {
        private const val CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        private const val CONTENT_STYLE_LIST = 1

        private const val ROOT = "ROOT"
        private const val VOD = "VOD"
        private const val LIVE = "LIVE"

        private val browseResultMap: ExpiringLruCache<String, Result> =
            ExpiringLruCache(64, TimeUnit.MINUTES.toMillis(5))
        private val ROOT_ITEM: MediaItem = folder(ROOT, "無名")
        private val VOD_FOLDER: MediaItem = folder(VOD, "點播")
        private val LIVE_FOLDER: MediaItem

        init {
            val extras = Bundle()
            extras.putInt(CONTENT_STYLE_BROWSABLE_HINT, CONTENT_STYLE_LIST)
            val meta = MediaMetadata.Builder()
                .setTitle("直播")
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .setExtras(extras)
                .build()
            LIVE_FOLDER = MediaItem.Builder().setMediaId(LIVE).setMediaMetadata(meta).build()
        }

        @JvmStatic
        fun clear() {
            browseResultMap.clear()
            clearVod()
            clearLive()
        }

        @JvmStatic
        fun clearVod() {
            browseResultMap.clear()
            VodBrowse.clear()
        }

        @JvmStatic
        fun clearLive() {
            browseResultMap.clear()
            LiveBrowse.clear()
        }

        @JvmStatic
        fun getRootItem(): MediaItem = ROOT_ITEM

        @JvmStatic

        fun getChildren(parentId: String): ImmutableList<MediaItem> = when (parentId) {
            ROOT -> ImmutableList.of(VOD_FOLDER, LIVE_FOLDER)
            VOD -> VodBrowse.getHistory()
            LIVE -> LiveBrowse.getGroups()
            else -> {
                if (parentId.startsWith(LiveBrowse.LIVE_GROUP)) LiveBrowse.getChannels(parentId)
                else ImmutableList.of()
            }
        }

        @JvmStatic

        fun getItem(mediaId: String): MediaItem? = when (mediaId) {
            ROOT -> ROOT_ITEM
            VOD -> VOD_FOLDER
            LIVE -> LIVE_FOLDER
            else -> {
                if (mediaId.startsWith(LiveBrowse.LIVE_GROUP)) folder(mediaId, mediaId.substring(LiveBrowse.LIVE_GROUP.length))
                else null
            }
        }

        @JvmStatic

        fun search(query: String): ImmutableList<MediaItem> = VodBrowse.search(query)

        @JvmStatic

        fun getSearchResult(): ImmutableList<MediaItem> = VodBrowse.getSearchResult()

        @JvmStatic

        @Throws(Exception::class)
        fun resolve(mediaId: String): MediaItem? {
            if (mediaId.startsWith(LiveBrowse.LIVE_CH)) return LiveBrowse.resolve(mediaId)
            return VodBrowse.resolve(mediaId)
        }

        @JvmStatic

        fun resolveOrKeep(item: MediaItem): MediaItem {
            try {
                val resolved = resolve(item.mediaId)
                return resolved ?: item
            } catch (e: Exception) {
                return item
            }
        }

        @JvmStatic

        @Throws(Exception::class)
        fun navigate(mediaId: String, delta: Int): MediaItem? {
            val vod = VodBrowse.navigate(mediaId, delta)
            if (vod != null) return vod
            return LiveBrowse.navigate(mediaId, delta)
        }

        @JvmStatic
        fun consumeResumePosition(): Long = VodBrowse.consumeResumePosition()

        @JvmStatic
        fun saveProgress(position: Long, duration: Long): Boolean = VodBrowse.saveProgress(position, duration)

        @JvmStatic

        fun consumeBrowseResult(mediaId: String): Result? = browseResultMap.remove(mediaId)

        @JvmStatic
        fun putBrowseResult(mediaId: String, result: Result) {
            browseResultMap.put(mediaId, result)
        }

        @JvmStatic
        fun wrapIndex(current: Int, delta: Int, count: Int): Int = ((current + delta) % count + count) % count

        @JvmStatic
        fun folder(id: String, title: String): MediaItem =
            build(id, true, false, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, title, null, null, null)

        @JvmStatic
        fun playable(id: String, title: String, subtitle: String?, art: String?): MediaItem =
            build(id, false, true, MediaMetadata.MEDIA_TYPE_VIDEO, title, subtitle, art, null)

        @JvmStatic
        fun stream(id: String, url: String, title: String, subtitle: String?, art: String?): MediaItem =
            build(id, false, true, MediaMetadata.MEDIA_TYPE_VIDEO, title, subtitle, art, Uri.parse(url))

        private fun build(id: String, browsable: Boolean, playable: Boolean, mediaType: Int, title: String, subtitle: String?, art: String?, uri: Uri?): MediaItem {
            val metadata = MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(browsable)
                .setIsPlayable(playable)
                .setMediaType(mediaType)
            if (BeanUtils.isNotEmpty(subtitle)) metadata.setSubtitle(subtitle)
            if (BeanUtils.isNotEmpty(art)) metadata.setArtworkUri(Uri.parse(art))
            if (BeanUtils.isNotEmpty(subtitle) && uri != null) metadata.setArtist(subtitle)
            val builder = MediaItem.Builder().setMediaId(id).setMediaMetadata(metadata.build())
            if (uri != null) builder.setUri(uri)
            return builder.build()
        }
    }
}
