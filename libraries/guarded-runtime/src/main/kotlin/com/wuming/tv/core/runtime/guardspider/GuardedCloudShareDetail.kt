package com.wuming.tv.core.runtime.guardspider

import com.wuming.tv.core.catalog.CatalogDetail
import com.wuming.tv.core.catalog.CatalogEpisode
import com.wuming.tv.core.catalog.CatalogItem
import com.wuming.tv.core.catalog.CatalogLine
import com.google.gson.JsonElement
import com.google.gson.JsonParser

/** Desktop equivalent of the Android Pan Activity decoration for search-result share payloads. */
internal object GuardedCloudShareDetail {
    fun decode(sourceKey: String, id: String): CatalogDetail? {
        val root = runCatching { JsonParser.parseString(id).asJsonObject }.getOrNull() ?: return null
        val shares = root.entrySet().flatMap { (provider, value) ->
            val entries = if (value.isJsonArray) value.asJsonArray.toList() else emptyList()
            entries.mapNotNull { entry ->
                val item = entry.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@mapNotNull null
                val url = item.get("url")?.takeIf(JsonElement::isJsonPrimitive)?.asString?.trim().orEmpty()
                if (!url.startsWith("https://") && !url.startsWith("http://")) return@mapNotNull null
                val note = item.get("note")?.takeIf(JsonElement::isJsonPrimitive)?.asString?.trim().orEmpty()
                val password = item.get("password")?.takeIf(JsonElement::isJsonPrimitive)?.asString?.trim().orEmpty()
                Share(provider, note.ifBlank { "Cloud share" }, url, password)
            }
        }
        if (shares.isEmpty()) return null
        val title = shares.first().note
        val lines = shares.groupBy(Share::provider).map { (provider, values) ->
            CatalogLine(
                flag = provider,
                name = provider.displayName(),
                episodes = values.map { share -> CatalogEpisode(share.note, share.url) },
            )
        }
        return CatalogDetail(
            item = CatalogItem(sourceKey = sourceKey, id = id, name = title),
            content = buildString {
                append("Cloud-drive search result.")
                shares.map(Share::password).filter(String::isNotBlank).distinct().takeIf(List<String>::isNotEmpty)
                    ?.let { append(" Access code: ${it.joinToString()}") }
            },
            lines = lines,
        )
    }

    private fun String.displayName(): String = when (lowercase()) {
        "quark" -> "Quark"
        "uc" -> "UC"
        "ali", "alipan" -> "Aliyun Drive"
        "baidu" -> "Baidu Netdisk"
        else -> this
    }

    private data class Share(val provider: String, val note: String, val url: String, val password: String)
}
