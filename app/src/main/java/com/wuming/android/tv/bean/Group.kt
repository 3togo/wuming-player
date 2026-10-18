package com.wuming.android.tv.bean

import androidx.annotation.Nullable
import androidx.annotation.StringRes
import com.wuming.android.tv.App
import com.wuming.android.tv.R
import com.wuming.android.tv.utils.ResUtil
import com.github.catvod.utils.Trans
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken

open class Group {

    @SerializedName("channel")
    private var channel: MutableList<Channel>? = null

    @SerializedName("name")
    private var name: String = ""

    @SerializedName("pass")
    private var pass: String? = null

    private var selected: Boolean = false
    private var position: Int = -1
    private var width: Int = 0

    constructor(name: String) : this(name, false)

    constructor(name: String, pass: Boolean) {
        this.name = name
        this.position = -1
        if (name.contains("_")) parse(pass)
        if (this.name.isEmpty()) setName(ResUtil.getString(R.string.setting_live))
    }

    private fun parse(pass: Boolean) {
        val splits = name.split("_", limit = 2)
        setName(splits[0])
        if (pass || splits.size == 1) return
        setPass(splits[1])
    }

    fun getChannel(): MutableList<Channel> {
        if (channel == null) channel = ArrayList()
        return channel!!
    }

    fun setChannel(channel: List<Channel>?) {
        this.channel = channel?.toMutableList()
    }

    fun getName(): String = BeanUtils.emptyIfNull(name)

    fun setName(name: String) {
        this.name = name
    }

    fun getPass(): String = BeanUtils.emptyIfNull(pass)

    fun setPass(pass: String?) {
        this.pass = pass
    }

    fun isSelected(): Boolean = selected

    fun setSelected(selected: Boolean) {
        this.selected = selected
    }

    fun getPosition(): Int = position

    fun setPosition(position: Int) {
        this.position = position
    }

    fun getWidth(): Int = width

    fun setWidth(width: Int) {
        this.width = width
    }

    fun isHidden(): Boolean = BeanUtils.isNotEmpty(getPass())

    fun isKeep(): Boolean = getName() == ResUtil.getString(R.string.keep)

    fun isEmpty(): Boolean = getChannel().isEmpty()

    open fun skip(): Boolean = isKeep()

    fun find(number: Int): Int = getChannel().lastIndexOf(Channel.create(number))

    fun find(name: String): Int = getChannel().lastIndexOf(Channel.create(name))

    fun add(channel: Channel) {
        for (item in getChannel()) {
            if (item == channel) {
                item.urls.addAll(channel.urls)
                return
            }
        }
        getChannel().add(Channel.create(channel))
    }

    fun find(channel: Channel): Channel {
        for (item in getChannel()) {
            if (item == channel) return item
        }
        getChannel().add(channel)
        return channel
    }

    fun current(): Channel? {
        val channels = getChannel()
        val pos = getPosition()
        if (pos < 0 || pos >= channels.size) return null
        return channels[pos].group(this)
    }

    fun trans(): Group {
        if (Trans.pass()) return this
        this.name = Trans.s2t(name) ?: name
        return this
    }

    override fun equals(@Nullable other: Any?): Boolean {
        if (other == null) return false
        if (this === other) return true
        if (other !is Group) return false
        return getName() == other.getName() && getChannel().size == other.getChannel().size
    }

    override fun hashCode(): Int = java.util.Objects.hash(getName(), getChannel().size)

    companion object {
        @JvmStatic
        fun arrayFrom(str: String): List<Group> {
            val listType = object : TypeToken<List<Group>>() {}.type
            val items = App.gson().fromJson<List<Group>>(str, listType)
            return BeanUtils.emptyListIfNull(items)
        }

        @JvmStatic
        fun create(): Group = create(R.string.setting_live)

        @JvmStatic
        fun create(@StringRes resId: Int): Group = Group(ResUtil.getString(resId))

        @JvmStatic
        fun create(name: String, pass: Boolean): Group = Group(name, pass)
    }
}
