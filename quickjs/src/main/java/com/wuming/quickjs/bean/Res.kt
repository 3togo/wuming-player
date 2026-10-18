package com.wuming.quickjs.bean

import com.github.catvod.utils.Json
import com.github.catvod.utils.Util
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import java.io.ByteArrayInputStream

class Res {

    @SerializedName("code")
    private var code: Int? = null

    @SerializedName("buffer")
    private var buffer: Int? = null

    @SerializedName("content")
    private var content: String? = null

    @SerializedName("headers")
    private var headers: JsonElement? = null

    fun getCode(): Int = code ?: 200

    fun getBuffer(): Int = buffer ?: 0

    fun getContent(): String = if (content.isNullOrEmpty()) "" else content!!

    private fun getHeaders(): JsonElement? = headers

    fun getHeader(): Map<String, String>? {
        val h = getHeaders() ?: return null
        return Json.toMap(h)
    }

    fun getContentType(): String {
        val header = getHeader() ?: return "application/octet-stream"
        val keys = listOf("Content-Type", "content-type")
        for (key in keys) if (header.containsKey(key)) return header[key]!!
        return "application/octet-stream"
    }

    fun getStream(): ByteArrayInputStream {
        return if (getBuffer() == 2) ByteArrayInputStream(Util.decode(getContent()))
        else ByteArrayInputStream(getContent().toByteArray())
    }

    companion object {

        @JvmStatic
        fun objectFrom(json: String?): Res = Json.GSON.fromJson(json, Res::class.java)
    }
}
