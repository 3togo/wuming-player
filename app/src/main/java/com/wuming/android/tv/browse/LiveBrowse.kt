package com.wuming.android.tv.browse

import androidx.media3.common.MediaItem

import com.wuming.android.tv.api.LiveApi
import com.wuming.android.tv.api.config.LiveConfig
import com.wuming.android.tv.bean.BeanUtils
import com.wuming.android.tv.bean.Channel
import com.wuming.android.tv.bean.Group
import com.wuming.android.tv.bean.Live
import com.wuming.android.tv.bean.Result
import com.wuming.android.tv.db.AppDatabase
import com.wuming.tv.core.cache.BoundedLruMap
import com.google.common.collect.ImmutableList

import java.util.stream.IntStream

class LiveBrowse {

    companion object {
        const val LIVE_GROUP = "LG:"
        const val LIVE_CH = "LC:"

        private const val LIVE_NAV_MAX_SIZE = 512
        private const val LIVE_COUNT_MAX_SIZE = 64
        private const val LIVE_POS_MAX_SIZE = 1024

        private val liveNavMap: MutableMap<String, String> = BoundedLruMap.create(LIVE_NAV_MAX_SIZE)
        private val liveCountMap: MutableMap<String, Int> = BoundedLruMap.create(LIVE_COUNT_MAX_SIZE)
        private val livePosMap: MutableMap<String, LiveEntry> = BoundedLruMap.create(LIVE_POS_MAX_SIZE)

        @JvmStatic
        fun clear() {
            livePosMap.clear()
            liveNavMap.clear()
            liveCountMap.clear()
        }

        @JvmStatic
        fun trim() {
            val navIt = liveNavMap.entries.iterator()
            var navRemoved = 0
            while (navIt.hasNext() && navRemoved < LIVE_NAV_MAX_SIZE / 2) {
                navIt.next()
                navIt.remove()
                navRemoved++
            }
            val posIt = livePosMap.entries.iterator()
            var posRemoved = 0
            while (posIt.hasNext() && posRemoved < LIVE_POS_MAX_SIZE / 2) {
                posIt.next()
                posIt.remove()
                posRemoved++
            }
            val countIt = liveCountMap.entries.iterator()
            var countRemoved = 0
            while (countIt.hasNext() && countRemoved < LIVE_COUNT_MAX_SIZE / 2) {
                countIt.next()
                countIt.remove()
                countRemoved++
            }
        }

        @JvmStatic
        fun getGroups(): ImmutableList<MediaItem> {
            return ImmutableList.copyOf(liveHome().getGroups().map { BrowseTree.folder(LIVE_GROUP + it.getName(), it.getName()) })
        }

        @JvmStatic
        fun getChannels(parentId: String): ImmutableList<MediaItem> {
            val groupName = parentId.substring(LIVE_GROUP.length)
            val group = liveHome().getGroups().firstOrNull { it.getName() == groupName }
            if (group == null) {
                liveCountMap.put(groupName, 0)
                return ImmutableList.of()
            }
            liveNavMap.keys.removeIf { it.startsWith("$groupName|") }
            livePosMap.values.removeIf { it.groupName == groupName }
            val channels = group.getChannel()
            liveCountMap.put(groupName, channels.size)
            return ImmutableList.copyOf((0 until channels.size).map { indexChannel(groupName, channels[it], it) })
        }

        private fun indexChannel(groupName: String, channel: Channel, index: Int): MediaItem {
            val key = liveNavKey(groupName, index)
            val id = LIVE_CH + key
            liveNavMap.put(key, id)
            livePosMap.put(id, LiveEntry(channel, groupName, index))
            return BrowseTree.playable(id, channel.getNumber() + " " + channel.getShow(), null, channel.getLogo())
        }

        @JvmStatic
        @Throws(Exception::class)
        fun resolve(mediaId: String): MediaItem? {
            val entry = livePosMap.get(mediaId)
            return resolveChannel(entry?.channel, mediaId)
        }

        @JvmStatic
        @Throws(Exception::class)
        fun navigate(mediaId: String, delta: Int): MediaItem? {
            if (mediaId.startsWith(LIVE_CH)) return navigateChannel(mediaId, delta)
            val liveId = ensureLoaded()
            return if (liveId != null) navigateChannel(liveId, delta) else null
        }

        @Throws(Exception::class)
        private fun navigateChannel(mediaId: String, delta: Int): MediaItem? {
            val current = livePosMap.get(mediaId) ?: return null
            val count = liveCountMap.get(current.groupName)
            if (count == null || count == 0) return null
            val target = BrowseTree.wrapIndex(current.index, delta, count)
            val nextId = liveNavMap.get(liveNavKey(current.groupName, target)) ?: return null
            val next = livePosMap.get(nextId)
            return resolveChannel(next?.channel, nextId)
        }

        private fun ensureLoaded(): String? {
            val keep = liveHome().getKeep()
            if (BeanUtils.isEmpty(keep)) return null
            val splits = keep.split(AppDatabase.SYMBOL)
            if (splits.size < 2) return null
            val groupName = splits[0]
            val channelName = splits[1]
            getChannels(LIVE_GROUP + groupName)
            val count = liveCountMap.get(groupName)
            if (count == null || count == 0) return null
            return livePosMap.entries.firstOrNull { it.value.groupName == groupName && it.value.channel.getName() == channelName }?.key
        }

        @Throws(Exception::class)
        private fun resolveChannel(channel: Channel?, mediaId: String): MediaItem? {
            if (channel == null || BeanUtils.isEmpty(channel.getCurrent())) return null
            val result = LiveApi.getUrl(channel)
            if (BeanUtils.isEmpty(result.getRealUrl())) return null
            BrowseTree.putBrowseResult(mediaId, result)
            return BrowseTree.stream(mediaId, result.getRealUrl(), channel.getShow(), null, channel.getLogo())
        }

        private fun liveHome(): Live {
            LiveConfig.get().ensureLoaded()
            return LiveConfig.get().getHome()
        }

        private fun liveNavKey(groupName: String, index: Int): String {
            return "$groupName|$index"
        }

        data class LiveEntry(val channel: Channel, val groupName: String, val index: Int)
    }
}
