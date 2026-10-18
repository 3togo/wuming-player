package com.wuming.android.tv.bean

import android.net.Uri
import com.wuming.android.tv.App
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

class Url {
    @SerializedName("values")
    private var values: MutableList<Value>? = null
    @SerializedName("position")
    private var position: Int = 0

    fun getValues(): MutableList<Value> {
        if (values == null) values = ArrayList()
        return values!!
    }

    fun getPosition(): Int = position

    fun uri(): Uri = Uri.parse(v())

    fun v(): String = v(position)

    fun v(position: Int): String =
        if (position >= getValues().size) "" else getValues()[position].getV()

    fun n(position: Int): String =
        if (position >= getValues().size) "" else getValues()[position].getN()

    fun add(v: String): Url {
        getValues().add(Value.create(v))
        return this
    }

    fun add(n: String, v: String): Url {
        getValues().add(Value.create(n, v))
        return this
    }

    fun replace(url: String): Url {
        if (getValues().isEmpty()) add(url)
        else getValues()[position].setV(url)
        return this
    }

    fun set(position: Int): Url {
        this.position = minOf(position, getValues().size - 1)
        return this
    }

    fun isEmpty(): Boolean = getValues().isEmpty() || BeanUtils.isEmpty(v())

    fun isMulti(): Boolean = getValues().size > 1

    companion object {
        @JvmStatic
        fun objectFrom(element: JsonElement?): Url {
            return try {
                App.gson().fromJson(element, Url::class.java)
            } catch (e: Exception) {
                create()
            }
        }

        @JvmStatic
        fun create(): Url = Url()
    }
}
