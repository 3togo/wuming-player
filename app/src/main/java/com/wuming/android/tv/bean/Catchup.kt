package com.wuming.android.tv.bean

import com.wuming.tv.core.epg.CatchupTemplate
import com.wuming.tv.core.epg.CatchupUrlPolicy
import com.google.gson.annotations.SerializedName
import java.time.ZoneId
import java.util.Locale

class Catchup {
    @SerializedName("type")
    private var type: String? = null
    @SerializedName("days")
    private var days: String? = null
    @SerializedName("regex")
    private var regex: String? = null
    @SerializedName("source")
    private var source: String? = null
    @SerializedName("replace")
    private var replace: String? = null

    fun getType(): String = BeanUtils.emptyIfNull(type)

    fun setType(type: String?) {
        this.type = type
    }

    fun getDays(): String = BeanUtils.emptyIfNull(days)

    fun setDays(days: String?) {
        this.days = days
    }

    fun getRegex(): String = BeanUtils.emptyIfNull(regex)

    fun setRegex(regex: String?) {
        this.regex = regex
    }

    fun getReplace(): String = BeanUtils.emptyIfNull(replace)

    fun setReplace(replace: String?) {
        this.replace = replace
    }

    fun getSource(): String = BeanUtils.emptyIfNull(source)

    fun setSource(source: String?) {
        this.source = source
    }

    fun match(url: String): Boolean = CatchupUrlPolicy.matches(toTemplate(), url)

    fun isEmpty(): Boolean = getSource().isEmpty()

    fun format(url: String, data: EpgData): String =
        CatchupUrlPolicy.format(toTemplate(), url, data.startTime, data.endTime,
            ZoneId.systemDefault(), Locale.getDefault())

    private fun toTemplate(): CatchupTemplate =
        CatchupTemplate(getType(), getSource(), getRegex(), getReplace())

    companion object {
        private val EMPTY = Catchup()

        @JvmStatic
        fun PLTV(): Catchup {
            val item = Catchup()
            item.setDays("7")
            item.setType("append")
            item.setRegex("/PLTV/")
            item.setReplace("/PLTV/,/TVOD/")
            item.setSource("?playseek=\${(b)yyyyMMddHHmmss}-\${(e)yyyyMMddHHmmss}")
            return item
        }

        @JvmStatic
        fun create(): Catchup = Catchup()

        @JvmStatic
        fun empty(): Catchup = EMPTY

        @JvmStatic
        fun decide(major: Catchup?, minor: Catchup?): Catchup? {
            if (major != null && !major.isEmpty()) return major
            if (minor != null && !minor.isEmpty()) return minor
            return null
        }
    }
}
