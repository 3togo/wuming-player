package com.wuming.android.tv.bean

import com.wuming.android.tv.App
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken

class Depot {
    @SerializedName("url")
    private var _url: String? = null

    @SerializedName("name")
    private var _name: String? = null

    @SerializedName("category")
    private var _category: String? = null

    fun getUrl(): String = BeanUtils.emptyIfNull(_url)

    fun getName(): String = if (BeanUtils.emptyIfNull(_name).isEmpty()) getUrl() else BeanUtils.emptyIfNull(_name)

    fun getCategory(): String = BeanUtils.emptyIfNull(_category)

    companion object {
        @JvmStatic
        fun arrayFrom(str: String?): List<Depot> {
            val listType = object : TypeToken<List<Depot>>() {}.type
            val items: List<Depot>? = App.gson().fromJson(str, listType)
            return BeanUtils.emptyListIfNull(items)
        }
    }
}
