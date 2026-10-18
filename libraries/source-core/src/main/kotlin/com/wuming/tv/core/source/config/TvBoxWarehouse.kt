package com.wuming.tv.core.source.config

import com.google.gson.JsonParser
import com.wuming.tv.core.source.TvBoxConfigPayloadDecoder
import java.net.URI
import java.net.URL

data class TvBoxWarehouseChild(val name: String, val url: String) {
    override fun toString(): String = name
}

/** Parses a TVBox `urls` index without fetching or activating any child configuration. */
object TvBoxWarehouse {
    @JvmStatic
    fun isLikelyIndex(name: String, flagged: Boolean): Boolean = flagged ||
        listOf("多仓", "多倉", "仓库", "倉庫").any { it in name }

    @JvmStatic
    fun parseOrNull(payload: ByteArray, base: String): List<TvBoxWarehouseChild>? {
        val root = runCatching {
            JsonParser.parseString(TvBoxConfigPayloadDecoder.decode(payload)).asJsonObject
        }.getOrNull() ?: return null
        val urls = root.get("urls")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        val seen = mutableSetOf<String>()
        return urls.mapNotNull { item ->
            val obj = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val raw = obj.get("url")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString?.trim().orEmpty()
            if (raw.isEmpty()) return@mapNotNull null
            val resolved = runCatching { ConfigResourceResolver.resolve(base, raw) }.getOrNull()
                ?: return@mapNotNull null
            if (resolved.any { it.isWhitespace() || Character.isISOControl(it) }) return@mapNotNull null
            val valid = when (resolved.substringBefore(':').lowercase()) {
                "http", "https" -> runCatching { URL(resolved) }.getOrNull()
                    ?.let { it.host.isNotBlank() && '@' !in it.authority } == true
                "file", "assets" -> runCatching { URI(resolved) }.isSuccess
                else -> false
            }
            if (!valid || !seen.add(resolved)) return@mapNotNull null
            val name = obj.get("name")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString?.trim().orEmpty().ifBlank { resolved }
            TvBoxWarehouseChild(name, resolved)
        }.take(100)
    }
}
