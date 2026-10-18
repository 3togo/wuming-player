package com.wuming.android.tv.bean

import androidx.annotation.NonNull
import androidx.annotation.Nullable
import com.wuming.android.tv.App
import com.wuming.android.tv.R
import com.wuming.android.tv.gson.HeaderAdapter
import com.wuming.android.tv.impl.Diffable
import com.wuming.android.tv.utils.ResUtil
import com.wuming.android.tv.utils.UrlUtil
import com.github.catvod.utils.Util
import com.google.gson.JsonElement
import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName
import java.util.HashMap
import java.util.Objects

class Parse : Diffable<Parse> {

    @SerializedName("name")
    private var name: String? = null

    @SerializedName("type")
    private var type: Int? = null

    @SerializedName("url")
    private var url: String? = null

    @SerializedName("ext")
    private var ext: Ext? = null

    private var activated: Boolean = false
    private var click: String? = null

    fun getName(): String = BeanUtils.emptyIfNull(name)

    fun setName(name: String?) { this.name = name }

    fun getType(): Int = BeanUtils.getOrDefault(type, 0)

    fun setType(type: Int?) { this.type = type }

    fun getUrl(): String = BeanUtils.emptyOrTransform(url) { UrlUtil.convert(it) }

    fun setUrl(url: String?) { this.url = url }

    fun getExt(): Ext {
        if (ext == null) ext = Ext()
        return ext!!
    }

    fun isActivated(): Boolean = activated

    fun setActivated(activated: Boolean) { this.activated = activated }

    fun setActivated(item: Parse) { this.activated = item == this }

    fun getClick(): String = BeanUtils.emptyIfNull(click)

    fun setClick(click: String?) { this.click = click }

    fun getHeader(): Map<String, String> = getExt().getHeader()

    fun setHeader(header: Map<String, String>) {
        if (getHeader().isEmpty()) getExt().setHeader(header)
    }

    fun isEmpty(): Boolean = getType() == 0 && getUrl().isEmpty()

    fun copy(): Parse {
        val copy = Parse()
        copy.name = name
        copy.type = type
        copy.url = url
        copy.click = click
        copy.activated = activated
        if (ext != null) {
            copy.ext = Ext()
            copy.ext!!.setFlag(ArrayList(ext!!.getFlag()))
            copy.ext!!.setHeader(HashMap(ext!!.getHeader()))
        }
        return copy
    }

    fun extUrl(): String {
        val index = getUrl().indexOf("?")
        if (getExt().isEmpty() || index == -1) return getUrl()
        return getUrl().substring(0, index + 1) + "cat_ext=" + Util.base64(getExt().toString(), Util.URL_SAFE) + "&" + getUrl().substring(index + 1)
    }

    fun mixMap(): HashMap<String, String> {
        val map = HashMap<String, String>(4)
        map["type"] = getType().toString()
        map["ext"] = getExt().toString()
        map["url"] = getUrl()
        return map
    }

    override fun equals(@Nullable other: Any?): Boolean {
        if (this === other) return true
        if (other !is Parse) return false
        return Objects.equals(getName(), other.getName())
    }

    override fun hashCode(): Int = Objects.hash(getName())

    override fun isSameItem(other: Parse): Boolean = equals(other)

    override fun isSameContent(other: Parse): Boolean = equals(other)

    class Ext {

        @SerializedName("flag")
        private var flag: List<String>? = null

        @SerializedName("header")
        @JsonAdapter(HeaderAdapter::class)
        private var header: Map<String, String>? = null

        fun getFlag(): List<String> = BeanUtils.emptyListIfNull(flag)

        fun setFlag(flag: List<String>?) { this.flag = flag }

        fun getHeader(): Map<String, String> = BeanUtils.emptyMapIfNull(header)

        fun setHeader(header: Map<String, String>?) { this.header = header }

        fun isEmpty(): Boolean = getFlag().isEmpty() && getHeader().isEmpty()

        @NonNull
        override fun toString(): String = App.gson().toJson(this)
    }

    companion object {
        @JvmStatic
        fun objectFrom(element: JsonElement): Parse = App.gson().fromJson(element, Parse::class.java)

        @JvmStatic
        fun get(type: Int?, url: String?): Parse {
            val parse = Parse()
            parse.setType(type)
            parse.setUrl(url)
            return parse
        }

        @JvmStatic
        fun god(): Parse {
            val parse = Parse()
            parse.setName(ResUtil.getString(R.string.parse_god))
            parse.setType(4)
            return parse
        }
    }
}
