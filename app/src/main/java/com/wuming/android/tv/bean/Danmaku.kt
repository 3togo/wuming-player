package com.wuming.android.tv.bean

import com.wuming.android.tv.App
import com.wuming.android.tv.utils.UrlUtil
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import java.util.Objects

/** Mutable overlay selection; identity is the normalized URL, not all serialized fields. */
open class Danmaku {
    @field:SerializedName("name")
    private var name: String? = null

    @field:SerializedName("url")
    private var url: String? = null

    private var selected = false

    open fun getName(): String? = if (name.isNullOrEmpty()) getUrl() else name

    open fun setName(name: String?) {
        this.name = name
    }

    open fun getUrl(): String = url.orEmpty()

    open fun setUrl(url: String?) {
        this.url = url
    }

    open fun isSelected(): Boolean = selected

    open fun setSelected(selected: Boolean) {
        this.selected = selected
    }

    open fun isEmpty(): Boolean = getUrl().isEmpty()

    open fun getRealUrl(): String = UrlUtil.convert(if (getUrl().startsWith("/")) "file:/" + getUrl() else getUrl())

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Danmaku) return false
        return getUrl().equals(other.getUrl())
    }

    override fun hashCode(): Int = Objects.hashCode(getUrl())

    override fun toString(): String = App.gson().toJson(this)

    // `open` keeps the generated static bridges non-final for Java subclass factories.
    @Suppress("NON_FINAL_MEMBER_IN_OBJECT")
    companion object {
        @JvmStatic
        open fun arrayFrom(str: String?): List<Danmaku> {
            val listType = object : TypeToken<List<Danmaku>>() {}.type
            val items: List<Danmaku>? = App.gson().fromJson(str, listType)
            return BeanUtils.emptyListIfNull(items)
        }

        @JvmStatic
        open fun from(path: String?): Danmaku =
            Danmaku().apply {
                setName(path)
                setUrl(path)
            }

        @JvmStatic
        open fun empty(): Danmaku = Danmaku()
    }
}
