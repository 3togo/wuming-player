package com.github.catvod.utils

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

object Json {

    @JvmField
    val GSON: Gson = Gson()

    @JvmStatic
    fun parse(json: String?): JsonElement {
        return try {
            JsonParser.parseString(json)
        } catch (e: Throwable) {
            JsonObject()
        }
    }

    @JvmStatic
    fun isObj(text: String?): Boolean {
        if (text.isNullOrEmpty()) return false
        val trimmed = text.trim()
        return trimmed.startsWith("{") && trimmed.endsWith("}")
    }

    @JvmStatic
    fun isArray(text: String?): Boolean {
        if (text.isNullOrEmpty()) return false
        val trimmed = text.trim()
        return trimmed.startsWith("[") && trimmed.endsWith("]")
    }

    @JvmStatic
    fun isEmpty(obj: JsonObject, key: String): Boolean {
        if (!obj.has(key)) return true
        val element = obj.get(key)
        if (element.isJsonNull) return true
        if (element.isJsonArray) return element.asJsonArray.isEmpty()
        if (element.isJsonPrimitive && element.asJsonPrimitive.isString) return element.asString.trim().isEmpty()
        return true
    }

    @JvmStatic
    fun safeString(obj: JsonObject, key: String): String {
        return try {
            obj.getAsJsonPrimitive(key).asString.trim()
        } catch (e: Exception) {
            ""
        }
    }

    @JvmStatic
    fun safeListString(obj: JsonObject, key: String): List<String> {
        val result = mutableListOf<String>()
        if (!obj.has(key)) return result
        if (obj.get(key).isJsonObject) result.add(safeString(obj, key))
        else for (opt in obj.getAsJsonArray(key)) result.add(opt.asString)
        return result
    }

    @JvmStatic
    fun safeListElement(obj: JsonObject, key: String): List<JsonElement> {
        val result = mutableListOf<JsonElement>()
        if (!obj.has(key)) return result
        if (obj.get(key).isJsonObject) result.add(obj.get(key).asJsonObject)
        else for (opt in obj.getAsJsonArray(key)) result.add(opt.asJsonObject)
        return result
    }

    @JvmStatic
    fun safeObject(element: JsonElement): JsonObject {
        return try {
            var el = element
            if (el.isJsonPrimitive) el = parse(el.asJsonPrimitive.asString)
            el.asJsonObject
        } catch (e: Exception) {
            JsonObject()
        }
    }

    @JvmStatic
    fun toMap(json: String?): Map<String, String>? {
        return if (json.isNullOrEmpty()) null else toMap(parse(json))
    }

    @JvmStatic
    fun toMap(element: JsonElement): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val obj = safeObject(element)
        for ((key, _) in obj.entrySet()) map[key] = safeString(obj, key)
        return map
    }
}
