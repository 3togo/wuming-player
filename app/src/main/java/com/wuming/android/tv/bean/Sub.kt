package com.wuming.android.tv.bean

import androidx.media3.common.C
import com.wuming.android.tv.App
import com.wuming.android.tv.player.PlayerHelper
import com.wuming.android.tv.utils.UrlUtil
import com.github.catvod.utils.Trans
import com.google.gson.annotations.SerializedName

class Sub {
    @SerializedName("url")
    private var url: String? = null
    @SerializedName("name")
    private var name: String? = null
    @SerializedName("lang")
    private var lang: String? = null
    @SerializedName("format")
    private var format: String? = null
    @SerializedName("flag")
    private var flag: Int = 0

    fun getUrl(): String = BeanUtils.emptyIfNull(url)

    fun getName(): String = BeanUtils.emptyIfNull(name)

    fun getLang(): String = BeanUtils.emptyIfNull(lang)

    fun getFormat(): String = BeanUtils.emptyIfNull(format)

    fun getFlag(): Int = if (flag == 0) C.SELECTION_FLAG_DEFAULT else flag

    fun getRawFlag(): Int = flag

    fun isForced(): Boolean = (flag and C.SELECTION_FLAG_FORCED) != 0

    fun setFlag(flag: Int) {
        this.flag = flag
    }

    fun trans() {
        if (Trans.pass()) return
        this.name = Trans.s2t(name)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Sub) return false
        return getUrl() == other.getUrl()
    }

    override fun hashCode(): Int = getUrl().hashCode()

    override fun toString(): String = App.gson().toJson(this)

    companion object {
        @JvmStatic
        fun from(path: String?): Sub {
            val sub = Sub()
            sub.url = path
            sub.name = UrlUtil.path(path)
            sub.flag = C.SELECTION_FLAG_FORCED
            sub.format = PlayerHelper.getSubtitleMimeType(sub.name)
            return sub
        }

        @JvmStatic
        fun create(name: String?, url: String?, lang: String?, format: String?): Sub {
            val sub = Sub()
            sub.name = name
            sub.url = url
            sub.lang = lang
            sub.format = format
            return sub
        }
    }
}
