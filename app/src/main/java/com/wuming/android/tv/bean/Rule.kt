package com.wuming.android.tv.bean

import com.wuming.android.tv.App
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken

class Rule(
    @SerializedName("name") private var _name: String?
) {
    @SerializedName("hosts")
    private var _hosts: List<String>? = null
    @SerializedName("regex")
    private var _regex: List<String>? = null
    @SerializedName("script")
    private var _script: List<String>? = null
    @SerializedName("exclude")
    private var _exclude: List<String>? = null

    fun getName(): String = BeanUtils.emptyIfNull(_name)

    fun getHosts(): List<String> = BeanUtils.emptyListIfNull(_hosts)

    fun getRegex(): List<String> = BeanUtils.emptyListIfNull(_regex)

    fun getScript(): List<String> = BeanUtils.emptyListIfNull(_script)

    fun getExclude(): List<String> = BeanUtils.emptyListIfNull(_exclude)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Rule) return false
        return getName() == other.getName()
    }

    override fun hashCode(): Int = getName().hashCode()

    companion object {
        @JvmStatic
        fun create(name: String?): Rule = Rule(name)

        @JvmStatic
        fun empty(): Rule = Rule("")

        @JvmStatic
        fun arrayFrom(element: JsonElement?): List<Rule> {
            val listType = object : TypeToken<List<Rule>>() {}.type
            val items: List<Rule>? = App.gson().fromJson(element, listType)
            return BeanUtils.emptyListIfNull(items)
        }
    }
}
