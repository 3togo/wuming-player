package com.wuming.android.tv.api

import com.wuming.android.tv.R
import com.wuming.android.tv.api.config.LiveConfig
import com.wuming.android.tv.api.parser.EpgParser
import com.wuming.android.tv.api.parser.LiveParser
import com.wuming.android.tv.bean.Channel
import com.wuming.android.tv.bean.Epg
import com.wuming.android.tv.bean.EpgData
import com.wuming.android.tv.bean.Group
import com.wuming.android.tv.utils.UrlUtil
import com.wuming.android.tv.bean.Live
import com.wuming.android.tv.bean.Result
import com.wuming.android.tv.player.Source
import com.wuming.android.tv.utils.AppLog
import com.wuming.android.tv.utils.Formatters
import com.wuming.android.tv.utils.LiveLineProber
import com.github.catvod.net.OkHttp
import java.time.LocalDate
import java.time.ZoneId

open class LiveApi {
    companion object {
        @JvmStatic
        @Throws(Exception::class)
        fun parse(item: Live) {
            LiveParser.start(item.recent())
            item.getGroups().removeIf(Group::isEmpty)
            if (item.getGroups().isEmpty() || item.getGroups()[0].isKeep()) return
            item.getGroups().add(0, Group.create(R.string.keep))
            LiveConfig.get().applyKeepsToGroups(item.getGroups())
        }

        @JvmStatic fun parseXml(item: Live): Boolean {
            var successful = false
            for (url in item.getEpgXml()) successful = startXml(item, url) || successful
            return successful
        }

        @JvmStatic fun getEpg(
            item: Channel,
            zoneId: ZoneId,
        ): Epg {
            val today = LocalDate.now(zoneId).format(Formatters.DATE)
            for (offset in intArrayOf(-1, 0, 1)) fetchEpgDay(item, zoneId, offset)
            return (item.getDataList().firstOrNull { it.equal(today) } ?: Epg()).selected()
        }

        @JvmStatic
        @Throws(Exception::class)
        fun getUrl(item: Channel): Result {
            Source.get().stop()
            item.setIndex(LiveLineProber.select(item.getUrls(), item.getIndex()))
            val result = item.result()
            val fetched = Source.get().fetch(result)
            if (fetched != null && fetched.contains("[") && fetched.contains("]")) AppLog.i("LiveApi", "IPv6 live URL resolved")
            result.setUrl(fetched)
            LiveLineProber.probe(item.getUrls(), item.getHeaders())
            return result
        }

        @JvmStatic
        @Throws(Exception::class)
        fun getUrl(
            item: Channel,
            data: EpgData,
        ): Result {
            val result = getUrl(item)
            result.setUrl(item.getCatchup().format(result.getRealUrl(), data))
            if (item.isRtsp()) result.getHeader()["rtsp_range"] = data.getRange()
            return result
        }

        private fun startXml(
            item: Live,
            url: String,
        ): Boolean =
            try {
                EpgParser.start(item, url)
                true
            } catch (_: Exception) {
                false
            }

        private fun fetchEpgDay(
            item: Channel,
            zoneId: ZoneId,
            offset: Int,
        ) {
            try {
                val date = LocalDate.now(zoneId).plusDays(offset.toLong()).format(Formatters.DATE)
                val url = item.getEpg().replace("{date}", date)
                val need = UrlUtil.isHttp(url) && item.getDataList().none { it.equal(date) }
                if (need) item.setData(Epg.objectFrom(OkHttp.string(url), item.getTvgId(), zoneId))
            } catch (_: Exception) {
            }
        }
    }
}
