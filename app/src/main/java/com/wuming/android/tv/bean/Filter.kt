package com.wuming.android.tv.bean

import android.os.Parcel
import android.os.Parcelable
import com.wuming.android.tv.App
import com.github.catvod.utils.Trans
import com.google.common.base.Predicates
import com.google.common.collect.Iterables
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken

open class Filter : Parcelable {
    @field:SerializedName("key")
    private var key: String? = null

    @field:SerializedName("name")
    private var name: String? = null

    @field:SerializedName("init")
    private var init: String? = null

    @field:SerializedName("value")
    private var value: List<Value>? = null

    constructor()

    @Suppress("DEPRECATION")
    protected constructor(source: Parcel) {
        key = source.readString()
        name = source.readString()
        init = source.readString()
        val values = ArrayList<Value>()
        value = values
        source.readList(values, Value::class.java.classLoader)
    }

    open fun getKey(): String = BeanUtils.emptyIfNull(key)

    open fun getName(): String = BeanUtils.emptyIfNull(name)

    // Missing defaults must stay absent from category requests. An explicit empty
    // value can mean "All", so it is distinct from an unspecified default.
    open fun getInit(): String? = init

    open fun getValue(): List<Value> = BeanUtils.emptyListIfNull(value)

    open fun setActivated(v: String?): String? {
        getValue()
            .stream()
            .filter { it.equals(Value.create(v)) }
            .findFirst()
            .ifPresent { it.setActivated(true) }
        return v
    }

    open fun check(): Filter {
        // Gson can populate null entries; retain in-place removal and immutable-list failures.
        Iterables.removeIf(getValue(), Predicates.isNull())
        return this
    }

    open fun copy(): Filter {
        val copy = Filter()
        copy.key = key
        copy.name = name
        copy.init = init
        val values = ArrayList<Value>()
        copy.value = values
        getValue().forEach { values.add(it.copy()) }
        return copy
    }

    open fun trans(): Filter {
        if (Trans.pass()) return this
        getValue().forEach { it.trans() }
        return this
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(
        dest: Parcel,
        flags: Int,
    ) {
        dest.writeString(key)
        dest.writeString(name)
        dest.writeString(init)
        dest.writeList(value)
    }

    @Suppress("NON_FINAL_MEMBER_IN_OBJECT")
    companion object {
        @JvmStatic
        open fun objectFrom(element: JsonElement?): Filter? = App.gson().fromJson(element, Filter::class.java)

        @JvmStatic
        open fun arrayFrom(result: String?): List<Filter> {
            val type = object : TypeToken<List<Filter>>() {}.type
            val items: List<Filter>? = App.gson().fromJson(result, type)
            return BeanUtils.emptyListIfNull(items)
        }

        @JvmField
        val CREATOR: Parcelable.Creator<Filter> =
            object : Parcelable.Creator<Filter> {
                override fun createFromParcel(source: Parcel): Filter = Filter(source)

                override fun newArray(size: Int): Array<Filter?> = arrayOfNulls(size)
            }
    }
}
