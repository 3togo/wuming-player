package com.wuming.tv.core.source.config

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** Converts the observed ZyPlayer export fields to TVBox configuration fields. */
internal object TvBoxConfigSchema {
    fun normalize(root: JsonObject) {
        for (name in listOf("sites", "parses", "lives")) {
            wrappedArray(root, name)?.let { root.add(name, it) }
        }
        for ((alias, name) in listOf("iptv" to "lives", "analyzes" to "parses")) {
            if (!root.has(name)) wrappedArray(root, alias)?.let { root.add(name, it) }
        }
    }

    fun normalizeWarehouse(root: JsonObject) {
        val stores = root.get("storeHouse")?.takeIf { it.isJsonArray }?.asJsonArray ?: return
        val existing = root.get("urls")
        if (existing != null && !existing.isJsonArray) return
        val urls = existing?.asJsonArray ?: JsonArray()
        val seen = urls.mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject?.string("url") }.toMutableSet()
        for (item in stores) {
            if (!item.isJsonObject) continue
            val store = item.asJsonObject
            val url = store.string("sourceUrl") ?: continue
            if (!seen.add(url)) continue
            urls.add(JsonObject().apply {
                addProperty("url", url)
                store.string("sourceName")?.let { addProperty("name", it) }
            })
        }
        root.add("urls", urls)
        root.remove("storeHouse")
    }

    private fun wrappedArray(root: JsonObject, name: String): JsonArray? =
        root.get(name)?.takeIf { it.isJsonObject }?.asJsonObject?.get("data")
            ?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString?.trim()?.takeIf { it.isNotEmpty() }
}
