package com.wuming.android.tv.bean

import com.wuming.android.tv.App
import com.wuming.android.tv.api.parser.EpgParser
import com.wuming.android.tv.utils.Formatters
import com.github.catvod.utils.Json
import com.google.gson.annotations.SerializedName

import java.time.LocalDateTime
import java.time.ZoneId
import java.util.ArrayList
import java.util.LinkedHashSet

class Epg {

    @SerializedName("key")
    private var key: String? = null
    @SerializedName("date")
    private var date: String? = null
    @SerializedName("epg_data")
    private var list: List<EpgData>? = null

    private var width: Int = 0

    companion object {
        @JvmStatic
        fun objectFrom(str: String, key: String, zoneId: ZoneId): Epg {
            if (!Json.isObj(str)) return EpgParser.getEpg(str, key, zoneId)
            try {
                val item = App.gson().fromJson(str, Epg::class.java)
                item.setTime(zoneId)
                item.setKey(key)
                return item
            } catch (e: Exception) {
                return Epg()
            }
        }

        @JvmStatic
        fun create(key: String, date: String): Epg {
            val item = Epg()
            item.setKey(key)
            item.setDate(date)
            item.setList(ArrayList())
            return item
        }
    }

    fun getKey(): String = BeanUtils.emptyIfNull(key)

    fun setKey(key: String?) {
        this.key = key
    }

    fun getDate(): String = BeanUtils.emptyIfNull(date)

    fun setDate(date: String?) {
        this.date = date
    }

    fun getList(): List<EpgData> = BeanUtils.emptyListIfNull(list)

    fun setList(list: List<EpgData>?) {
        this.list = list
    }

    fun getWidth(): Int = width

    fun setWidth(width: Int) {
        this.width = width
    }

    fun equal(date: String): Boolean = getDate() == date

    private fun setTime(zoneId: ZoneId) {
        setList(ArrayList(LinkedHashSet(getList())))
        for (item in getList()) {
            item.startTime = parseEpgTime(getDate() + item.getStart(), zoneId)
            item.endTime = parseEpgTime(getDate() + item.getEnd(), zoneId)
            if (item.endTime < item.startTime) item.checkDay(zoneId)
            item.trans()
        }
    }

    fun getEpgData(): EpgData = getList().firstOrNull { it.isSelected() } ?: EpgData()

    fun selected(): Epg {
        if (getList().size > 50) {
            val current = findCurrentProgramBinary()
            if (current != null) {
                for (item in getList()) item.setSelected(item == current)
                return this
            }
        }
        for (item in getList()) item.setSelected(item.isInRange())
        return this
    }

    fun getSelected(): Int {
        for (i in getList().indices) if (getList()[i].isSelected()) return i
        return -1
    }

    fun getInRange(): Int {
        if (getList().size > 50) {
            val current = findCurrentProgramBinary()
            if (current != null) {
                return getList().indexOf(current)
            }
        }
        for (i in getList().indices) if (getList()[i].isInRange()) return i
        return -1
    }

    private fun findCurrentProgramBinary(): EpgData? {
        val list = getList()
        if (list.isEmpty()) return null

        val now = System.currentTimeMillis()
        var left = 0
        var right = list.size - 1

        while (left <= right) {
            val mid = left + (right - left) / 2
            val program = list[mid]

            if (now >= program.startTime && now < program.endTime) {
                return program
            } else if (now < program.startTime) {
                right = mid - 1
            } else {
                left = mid + 1
            }
        }

        return null
    }

    private fun parseEpgTime(source: String, zoneId: ZoneId): Long {
        try {
            val fmt = if (source.length > 16) Formatters.EPG_DT_LONG else Formatters.EPG_DT_SHORT
            return LocalDateTime.parse(source, fmt).atZone(zoneId).toInstant().toEpochMilli()
        } catch (ignored: Exception) {
            return 0L
        }
    }
}
