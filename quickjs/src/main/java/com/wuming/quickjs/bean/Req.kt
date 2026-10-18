package com.wuming.quickjs.bean

import com.github.catvod.utils.Json
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

class Req {

    @SerializedName("buffer")
    private var buffer: Int? = null

    @SerializedName("redirect")
    private var redirect: Int? = null

    @SerializedName("timeout")
    private var timeout: Int? = null

    @SerializedName("postType")
    private var postType: String? = null

    @SerializedName("method")
    private var method: String? = null

    @SerializedName("body")
    private var body: String? = null

    @SerializedName("data")
    private var data: JsonElement? = null

    @SerializedName("headers")
    private var headers: JsonElement? = null

    fun getBuffer(): Int = buffer ?: 0

    fun getRedirect(): Int = redirect ?: 1

    fun getTimeout(): Int = timeout ?: 10000

    fun getPostType(): String = if (postType.isNullOrEmpty()) "json" else postType!!

    fun getMethod(): String = if (method.isNullOrEmpty()) "get" else method!!

    fun getBody(): String? = body

    fun getData(): JsonElement? = data

    private fun getHeaders(): JsonElement? = headers

    fun isRedirect(): Boolean = getRedirect() == 1

    fun getHeader(): Map<String, String>? {
        val h = getHeaders() ?: return null
        return Json.toMap(h)
    }

    fun getCharset(): String {
        val header = getHeader() ?: return "UTF-8"
        val keys = listOf("Content-Type", "content-type")
        for (key in keys) if (header.containsKey(key)) return getCharset(header[key]!!)
        return "UTF-8"
    }

    private fun getCharset(value: String): String {
        for (text in value.split(";")) if (text.contains("charset=")) return text.split("=")[1]
        return "UTF-8"
    }

    companion object {

        @JvmStatic
        fun objectFrom(json: String?): Req = Json.GSON.fromJson(json, Req::class.java)
    }
}
