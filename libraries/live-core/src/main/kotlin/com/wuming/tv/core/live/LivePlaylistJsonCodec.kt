package com.wuming.tv.core.live

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Canonicalizes common playlist aliases while retaining platform-specific channel options. */
object LivePlaylistJsonCodec {
    @JvmStatic
    fun normalize(content: String): String = groups(content).toString()

    internal fun groups(content: String): JsonArray {
        val root = JsonParser.parseString(LivePlaylistEncoding.stripMark(content))
        require(root.isJsonArray) { "Live JSON playlist must be an array of groups" }
        val groups = JsonArray()
        for (entry in root.asJsonArray) {
            if (!entry.isJsonObject) continue
            val group = entry.asJsonObject.deepCopy()
            alias(group, "name", "group")
            val channels = (group.get("channel")?.takeIf { it.isJsonArray }
                ?: group.get("channels")?.takeIf { it.isJsonArray })?.asJsonArray ?: continue
            val normalized = JsonArray()
            for (item in channels) {
                if (!item.isJsonObject) continue
                val channel = item.asJsonObject.deepCopy()
                alias(channel, "tvgId", "tvg-id")
                alias(channel, "tvgName", "tvg-name")
                alias(channel, "logo", "tvg-logo")
                alias(channel, "number", "tvg-chno")
                alias(channel, "ua", "http-user-agent")
                alias(channel, "header", "headers")
                val locations = channel.get("urls")?.takeUnless { it.isJsonNull } ?: channel.get("url")
                val urls = JsonArray()
                val candidates = if (locations?.isJsonArray == true) locations.asJsonArray.toList() else listOfNotNull(locations)
                candidates.mapNotNull(::string).map(String::trim).filter(String::isNotEmpty)
                    .distinct().forEach(urls::add)
                channel.add("urls", urls)
                normalized.add(channel)
            }
            group.add("channel", normalized)
            groups.add(group)
        }
        return groups
    }

    internal fun text(item: JsonObject, key: String): String = string(item.get(key)).orEmpty()

    internal fun headers(item: JsonObject): Map<String, String> = linkedMapOf<String, String>().apply {
        var header = item.get("header")
        if (header?.isJsonPrimitive == true && header.asJsonPrimitive.isString) {
            header = runCatching { JsonParser.parseString(header.asString) }.getOrNull()
        }
        if (header?.isJsonObject == true) {
            for ((key, value) in header.asJsonObject.entrySet()) string(value)?.let { put(key, it) }
        }
        for ((key, name) in mapOf("ua" to "User-Agent", "referer" to "Referer", "origin" to "Origin")) {
            text(item, key).takeIf(String::isNotBlank)?.let { put(name, it) }
        }
    }

    private fun alias(item: JsonObject, key: String, alternate: String) {
        if (!item.has(key) || item.get(key).isJsonNull) item.get(alternate)?.let { item.add(key, it) }
    }

    private fun string(value: JsonElement?): String? = value?.takeIf {
        it.isJsonPrimitive && (it.asJsonPrimitive.isString || it.asJsonPrimitive.isNumber)
    }?.asString
}
