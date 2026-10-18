package com.wuming.android.tv.bean

import com.wuming.android.tv.utils.Formatters
import com.github.catvod.utils.Trans
import com.google.gson.annotations.SerializedName
import java.time.Instant
import java.time.ZoneId

class EpgData {

    @SerializedName("title")
    private var title: String? = null

    @SerializedName("start")
    private var start: String? = null

    @SerializedName("end")
    private var end: String? = null

    private var selected = false
    var startTime: Long = 0
    var endTime: Long = 0

    fun getTitle(): String = BeanUtils.emptyIfNull(title)

    fun setTitle(title: String?) {
        this.title = title
    }

    fun getStart(): String = BeanUtils.emptyIfNull(start)

    fun setStart(start: String?) {
        this.start = start
    }

    fun getEnd(): String = BeanUtils.emptyIfNull(end)

    fun setEnd(end: String?) {
        this.end = end
    }

    fun isSelected(): Boolean = selected

    fun setSelected(selected: Boolean) {
        this.selected = selected
    }

    fun setSelected(item: EpgData) {
        this.selected = item == this
    }

    fun isInRange(): Boolean =
        startTime <= System.currentTimeMillis() && System.currentTimeMillis() <= endTime

    fun isFuture(): Boolean = startTime > System.currentTimeMillis()

    fun format(): String {
        if (getTitle().isEmpty()) return ""
        if (getStart().isEmpty() && getEnd().isEmpty()) return getTitle()
        return getStart() + " ~ " + getEnd() + "  " + getTitle()
    }

    fun getTime(): String {
        if (getStart().isEmpty() && getEnd().isEmpty()) return ""
        return getStart() + " ~ " + getEnd()
    }

    fun getRange(): String =
        "clock=" + Formatters.EPG_RANGE.format(Instant.ofEpochMilli(startTime)) + "-" + Formatters.EPG_RANGE.format(Instant.ofEpochMilli(endTime))

    fun checkDay(zoneId: ZoneId) {
        endTime = Instant.ofEpochMilli(endTime).atZone(zoneId).plusDays(1).toInstant().toEpochMilli()
    }

    fun trans() {
        if (Trans.pass()) return
        title = Trans.s2t(title)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EpgData) return false
        return getTitle() == other.getTitle() && getEnd() == other.getEnd() && getStart() == other.getStart()
    }

    override fun hashCode(): Int = java.util.Objects.hash(getTitle(), getEnd(), getStart())
}
