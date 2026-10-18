package com.wuming.android.tv.bean

import com.wuming.android.tv.R
import com.wuming.android.tv.impl.Diffable
import com.wuming.android.tv.utils.ResUtil

open class Func(
    private val resId: Int,
) : Diffable<Func> {
    private var drawable = 0
    private var siteName: String? = null

    init {
        setDrawable()
    }

    companion object {
        const val TYPE_SITE = -1

        @JvmStatic fun create(resId: Int): Func = Func(resId)

        @JvmStatic fun createSite(siteName: String?): Func =
            Func(TYPE_SITE).also {
                it.siteName = siteName
                it.drawable = R.drawable.ic_home_vod
            }
    }

    open fun getResId(): Int = resId

    open fun getDrawable(): Int = drawable

    open fun getText(): String? = if (resId == TYPE_SITE) siteName else ResUtil.getString(resId)

    open fun isSite(): Boolean = resId == TYPE_SITE

    open fun setSiteName(siteName: String?) {
        this.siteName = siteName
    }

    open fun setDrawable() {
        when (resId) {
            R.string.home_vod -> drawable = R.drawable.ic_home_vod
            R.string.home_open_drive -> drawable = R.drawable.icon_drive
            R.string.home_live -> drawable = R.drawable.ic_home_live
            R.string.home_keep -> drawable = R.drawable.ic_home_keep
            R.string.home_playlist -> drawable = R.drawable.ic_home_playlist
            R.string.home_push -> drawable = R.drawable.ic_home_push
            R.string.home_search -> drawable = R.drawable.ic_home_search
            R.string.home_setting -> drawable = R.drawable.ic_home_setting
        }
    }

    override fun equals(other: Any?): Boolean = this === other || (other is Func && getResId() == other.getResId())

    override fun hashCode(): Int = Integer.hashCode(getResId())

    override fun isSameItem(other: Func): Boolean = equals(other)

    override fun isSameContent(other: Func): Boolean =
        if (resId ==
            TYPE_SITE
        ) {
            siteName != null && siteName == other.siteName
        } else {
            equals(other)
        }
}
