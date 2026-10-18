package com.wuming.tv.core.cloud

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.net.URI

/** Platform-neutral JSON helpers for cloud-drive providers. */
object CloudJson {

    @JvmStatic
    fun string(obj: JsonObject?, key: String): String {
        try {
            val value = obj?.get(key)
            return if (value == null || value.isJsonNull) "" else value.asString.trim()
        } catch (ignored: Exception) {
            return ""
        }
    }

    @JvmStatic
    fun number(obj: JsonObject?, key: String): Long {
        try {
            return if (obj == null || !obj.has(key)) 0L else obj.get(key).asLong
        } catch (ignored: Exception) {
            return 0L
        }
    }

    @JvmStatic
    fun integer(obj: JsonObject?, key: String): Int {
        try {
            return if (obj == null || !obj.has(key)) 0 else obj.get(key).asInt
        } catch (ignored: Exception) {
            return 0
        }
    }

    @JvmStatic
    fun first(obj: JsonObject?, vararg keys: String): String {
        for (key in keys) {
            val value = string(obj, key)
            if (value.isNotBlank()) return value
        }
        return ""
    }

    @JvmStatic
    fun bool(obj: JsonObject?, key: String): Boolean {
        try {
            return obj != null && obj.has(key) && obj.get(key).asBoolean
        } catch (ignored: Exception) {
            return false
        }
    }

    @JvmStatic
    fun jsonObject(parent: JsonObject?, key: String?): JsonObject {
        if (parent == null) return JsonObject()
        val value = if (key == null) parent else parent.get(key)
        return if (value != null && value.isJsonObject) value.asJsonObject else JsonObject()
    }

    @JvmStatic
    fun array(obj: JsonObject?, key: String?): JsonArray {
        if (obj == null) return JsonArray()
        val value = if (key == null) obj.get("data") else obj.get(key)
        return if (value != null && value.isJsonArray) value.asJsonArray else JsonArray()
    }

    @JvmStatic
    fun validDownloadUrl(value: String?): Boolean {
        try {
            val uri = URI.create(value ?: "")
            return "https".equals(uri.scheme, ignoreCase = true) && uri.host != null && uri.userInfo == null
        } catch (ignored: Exception) {
            return false
        }
    }
}
