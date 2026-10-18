package com.wuming.android.tv.bean

import android.os.Parcel
import android.os.Parcelable
import android.os.Parcelable.Creator
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import com.wuming.android.tv.App
import com.wuming.android.tv.impl.Diffable
import com.wuming.android.tv.utils.Util
import com.github.catvod.utils.Trans
import com.google.gson.annotations.SerializedName
import org.simpleframework.xml.Attribute
import org.simpleframework.xml.Text
import java.util.Comparator
import java.util.Locale
import java.util.Objects

class Flag : Parcelable, Diffable<Flag> {

    @Attribute(name = "flag", required = false)
    @SerializedName("flag")
    private var flag: String? = null

    private var show: String? = null

    @Text
    private var urls: String? = null

    @SerializedName("episodes")
    private var episodes: MutableList<Episode> = ArrayList()

    private var activated: Boolean = false
    private var position: Int = -1

    constructor() {
        this.position = -1
        this.episodes = ArrayList()
    }

    constructor(flag: String?) {
        this.flag = flag
        this.position = -1
        this.episodes = ArrayList()
    }

    protected constructor(`in`: Parcel) {
        flag = `in`.readString()
        show = `in`.readString()
        urls = `in`.readString()
        episodes = `in`.createTypedArrayList(Episode.CREATOR) ?: ArrayList()
        activated = `in`.readByte().toInt() != 0
        position = `in`.readInt()
    }

    fun getShow(): String = if (BeanUtils.isEmpty(show)) getFlag() else show!!

    fun getFlag(): String = BeanUtils.emptyIfNull(flag)

    fun setFlag(flag: String?) { this.flag = flag }

    fun getUrls(): String = BeanUtils.emptyIfNull(urls)

    fun getEpisodes(): MutableList<Episode> = episodes

    fun setEpisodes(url: String) {
        val urls: List<String> = if (url.contains("#")) url.split("#") else listOf(url)
        for (i in urls.indices) {
            val split = urls[i].split("\\$".toRegex(), 2)
            val number = String.format(Locale.getDefault(), "%02d", i + 1)
            val episode = if (split.size > 1) {
                Episode.create(if (split[0].isEmpty()) number else split[0].trim(), split[1])
            } else {
                Episode.create(number, urls[i])
            }
            if (!getEpisodes().contains(episode)) getEpisodes().add(episode)
        }
    }

    fun isActivated(): Boolean = activated

    fun setActivated(item: Flag) {
        this.activated = item == this
        if (activated) item.episodes = episodes
    }

    private fun setActivated(episode: Episode) {
        setPosition(getEpisodes().indexOf(episode))
        for (i in getEpisodes().indices) getEpisodes()[i].setActivated(i == getPosition())
    }

    fun getPosition(): Int = position

    fun setPosition(position: Int) { this.position = position }

    fun toggle(activated: Boolean, episode: Episode) {
        if (activated) setActivated(episode)
        else getEpisodes().forEach { it.deactivated() }
    }

    fun find(remarks: String, strict: Boolean): Episode? {
        if (getEpisodes().isEmpty()) return null
        if (getEpisodes().size == 1) return getEpisodes()[0]
        val number = Util.getNumber(remarks)
        return getEpisodes().stream()
            .map { episode -> Episode.Rule(episode, episode.getScore(remarks, number)) }
            .filter { it.find() }
            .max(Comparator.comparingInt { it.score })
            .map { it.episode }
            .orElseGet {
                when {
                    getPosition() != -1 -> getEpisodes()[getPosition()]
                    strict -> null
                    else -> getEpisodes()[0]
                }
            }
    }

    fun mergeEpisodes(items: List<Episode>, rev: Boolean) {
        for (item in items) {
            if (getEpisodes().contains(item)) continue
            if (rev) getEpisodes().add(0, item)
            else getEpisodes().add(item)
        }
    }

    fun trans(): Flag {
        if (Trans.pass()) return this
        show = Trans.s2t(flag)
        return this
    }

    override fun equals(@Nullable other: Any?): Boolean {
        if (this === other) return true
        if (other !is Flag) return false
        return Objects.equals(getFlag(), other.getFlag())
    }

    override fun hashCode(): Int = Objects.hash(getFlag())

    @NonNull
    override fun toString(): String = App.gson().toJson(this)

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(flag)
        dest.writeString(show)
        dest.writeString(urls)
        dest.writeTypedList(episodes)
        dest.writeByte(if (activated) 1 else 0)
        dest.writeInt(position)
    }

    override fun isSameItem(other: Flag): Boolean = equals(other)

    override fun isSameContent(other: Flag): Boolean = equals(other)

    companion object {
        @JvmStatic
        fun create(flag: String): Flag = Flag(flag).trans()

        @JvmStatic
        fun create(flag: String, url: String): Flag {
            val item = create(flag)
            item.setEpisodes(url)
            return item
        }

        @JvmField
        val CREATOR: Creator<Flag> = object : Creator<Flag> {
            override fun createFromParcel(source: Parcel): Flag = Flag(source)
            override fun newArray(size: Int): Array<Flag?> = arrayOfNulls(size)
        }
    }
}
