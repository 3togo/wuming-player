package com.github.catvod.bean

import com.github.catvod.utils.Json
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken

class Header {

    @SerializedName("host")
    private var host: String? = null

    @SerializedName("header")
    private var header: JsonElement? = null

    fun getHost(): String = if (host.isNullOrEmpty()) "" else host!!

    fun getHeader(): JsonElement? = header

    companion object {

        @JvmStatic
        fun arrayFrom(element: JsonElement?): List<Header> {
            return try {
                val listType = object : TypeToken<List<Header>>() {}.type
                val items: List<Header>? = Json.GSON.fromJson(element, listType)
                items ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}
