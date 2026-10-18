package com.wuming.android.tv.bean

import android.os.Parcel
import android.os.Parcelable
import android.os.Parcelable.Creator
import androidx.annotation.Nullable
import com.wuming.android.tv.impl.Diffable
import com.wuming.android.tv.utils.Util
import com.github.catvod.utils.Trans
import com.google.gson.annotations.SerializedName
import java.util.Objects

class Episode : Parcelable, Diffable<Episode> {

    @SerializedName("name")
    private var name: String? = null

    @SerializedName("desc")
    private var desc: String? = null

    @SerializedName("url")
    private var url: String? = null

    private var index: Int = 0
    private var number: Int = 0
    private var activated: Boolean = false
    private var selected: Boolean = false

    @Transient
    private var displayName: String? = null

    private constructor(name: String?, desc: String?, url: String?) {
        this.number = Util.getNumber(name ?: "")
        this.name = name
        this.desc = desc
        this.url = url
    }

    constructor()

    protected constructor(`in`: Parcel) {
        name = `in`.readString()
        desc = `in`.readString()
        url = `in`.readString()
        number = `in`.readInt()
        activated = `in`.readByte().toInt() != 0
        selected = `in`.readByte().toInt() != 0
    }

    fun getName(): String = BeanUtils.emptyIfNull(name)

    fun getDisplayName(): String {
        if (displayName == null) displayName = EpisodeDisplayName.format(getName())
        return displayName!!
    }

    fun setName(name: String?) {
        this.name = name
        displayName = null
    }

    fun getDesc(): String = BeanUtils.emptyIfNull(desc)

    fun getUrl(): String = BeanUtils.emptyIfNull(url)

    fun setUrl(url: String?) { this.url = url }

    fun getIndex(): Int = index

    fun setIndex(index: Int) { this.index = index }

    fun getNumber(): Int = number

    fun isActivated(): Boolean = activated

    fun setActivated(activated: Boolean) {
        this.activated = activated
        this.selected = activated
    }

    fun deactivated() { setActivated(false) }

    fun isSelected(): Boolean = selected

    fun setSelected(selected: Boolean) { this.selected = selected }

    fun getScore(name: String, number: Int): Int =
        com.wuming.tv.core.catalog.EpisodeMatchPolicy.score(getName(), getNumber(), name, number)

    fun matchesName(other: Episode?): Boolean {
        if (other == null) return false
        return getName().equals(other.getName(), ignoreCase = true)
    }

    fun trans(): Episode {
        if (Trans.pass()) return this
        setName(Trans.s2t(name))
        desc = Trans.s2t(desc)
        return this
    }

    override fun equals(@Nullable other: Any?): Boolean {
        if (this === other) return true
        if (other !is Episode) return false
        return Objects.equals(getName(), other.getName()) && Objects.equals(getUrl(), other.getUrl())
    }

    override fun hashCode(): Int = Objects.hash(getName(), getUrl())

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(name)
        dest.writeString(desc)
        dest.writeString(url)
        dest.writeInt(number)
        dest.writeByte(if (activated) 1 else 0)
        dest.writeByte(if (selected) 1 else 0)
    }

    override fun isSameItem(other: Episode): Boolean = equals(other)

    override fun isSameContent(other: Episode): Boolean =
        getUrl() == other.getUrl() && getDesc() == other.getDesc()

    data class Rule(val episode: Episode, val score: Int) {
        fun find(): Boolean = score > 0
    }

    companion object {
        @JvmStatic
        fun create(name: String, url: String): Episode = Episode(name, "", url).trans()

        @JvmStatic
        fun create(name: String, desc: String, url: String): Episode = Episode(name, desc, url).trans()

        @JvmField
        val CREATOR: Creator<Episode> = object : Creator<Episode> {
            override fun createFromParcel(source: Parcel): Episode = Episode(source)
            override fun newArray(size: Int): Array<Episode?> = arrayOfNulls(size)
        }
    }
}
