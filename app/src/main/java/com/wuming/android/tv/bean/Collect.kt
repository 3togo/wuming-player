package com.wuming.android.tv.bean

import android.os.Parcel
import android.os.Parcelable
import androidx.annotation.Nullable
import com.wuming.android.tv.R
import com.wuming.android.tv.impl.Diffable
import com.wuming.android.tv.utils.ResUtil
import java.util.ArrayList
import java.util.Objects

class Collect : Parcelable, Diffable<Collect> {
    private var activated: Boolean = false
    private var list: List<Vod>? = null
    private var site: Site? = null
    private var page: Int = 0

    constructor(site: Site?, list: List<Vod>?) {
        this.site = site
        this.list = list
    }

    @Suppress("deprecation")
    protected constructor(source: Parcel) {
        this.activated = source.readByte().toInt() != 0
        this.list = source.createTypedArrayList(Vod.CREATOR)
        this.site = source.readParcelable(Site::class.java.classLoader)
        this.page = source.readInt()
    }

    fun getSite(): Site = site ?: Site()

    fun getList(): List<Vod> = BeanUtils.emptyListIfNull(list)

    fun setList(list: List<Vod>) {
        this.list = list
    }

    fun isActivated(): Boolean = activated

    fun setActivated(activated: Boolean) {
        this.activated = activated
    }

    fun getPage(): Int = Math.max(1, page)

    fun setPage(page: Int) {
        this.page = page
    }

    override fun equals(@Nullable other: Any?): Boolean {
        if (this === other) return true
        if (other !is Collect) return false
        return Objects.equals(getSite(), other.getSite())
    }

    override fun hashCode(): Int = Objects.hashCode(getSite())

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeByte(if (activated) 1.toByte() else 0.toByte())
        dest.writeTypedList(list)
        dest.writeParcelable(site, flags)
        dest.writeInt(page)
    }

    override fun isSameItem(other: Collect): Boolean = equals(other)

    override fun isSameContent(other: Collect): Boolean = equals(other)

    companion object {
        @JvmStatic
        fun all(): Collect {
            val item = Collect(Site.get("all", ResUtil.getString(R.string.all)), ArrayList())
            item.setActivated(true)
            return item
        }

        @JvmStatic
        fun create(list: List<Vod>): Collect = Collect(if (list.isEmpty()) null else list[0].getSite(), list)

        @JvmField
        val CREATOR: Parcelable.Creator<Collect> = object : Parcelable.Creator<Collect> {
            override fun createFromParcel(source: Parcel): Collect = Collect(source)

            override fun newArray(size: Int): Array<Collect?> = arrayOfNulls(size)
        }
    }
}
