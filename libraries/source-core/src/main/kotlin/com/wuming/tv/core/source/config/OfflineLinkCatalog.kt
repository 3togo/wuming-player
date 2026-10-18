package com.wuming.tv.core.source.config

import com.google.gson.JsonParser
import java.net.URL

data class OfflineLinkGroup(
    val name: String,
    val kind: String,
    val entries: List<OfflineLinkEntry>,
) {
    override fun toString(): String = name
}

data class OfflineLinkEntry(val name: String, val url: String, val warehouse: Boolean = false) {
    override fun toString(): String = name
}

/** Browsing data only. Loading a chosen URL remains the platform's normal source flow. */
object OfflineLinkCatalog {
    @JvmStatic
    fun parse(payload: ByteArray): List<OfflineLinkGroup> {
        val root = JsonParser.parseString(payload.toString(Charsets.UTF_8)).asJsonObject
        require(root.get("version")?.asInt == 1) { "Unsupported offline link catalog." }
        val groups = root.getAsJsonArray("groups") ?: return emptyList()
        return groups.take(100).mapNotNull group@{ element ->
            val group = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@group null
            val kind = group.get("kind")?.takeIf { it.isJsonPrimitive }?.asString ?: return@group null
            if (kind !in setOf("vod", "live")) return@group null
            val name = group.get("name")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
            val rows = group.get("entries")?.takeIf { it.isJsonArray }?.asJsonArray ?: return@group null
            val seen = mutableSetOf<String>()
            val entries = rows.take(50).mapNotNull entry@{ row ->
                val item = row.takeIf { it.isJsonObject }?.asJsonObject ?: return@entry null
                val url = item.get("url")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
                val parsed = runCatching { URL(url) }.getOrNull() ?: return@entry null
                if (parsed.protocol !in setOf("http", "https") || parsed.host.isBlank() ||
                    '@' in parsed.authority || (parsed.query != null && !ProtectedSourceUrl.hasCredential(url)) ||
                    url.any(Character::isISOControl) ||
                    !seen.add(url)) return@entry null
                val label = item.get("name")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
                val warehouse = item.get("warehouse")?.takeIf { it.isJsonPrimitive }
                    ?.asBoolean == true
                OfflineLinkEntry(label.ifBlank { url }, url, warehouse)
            }
            if (entries.isEmpty()) null else OfflineLinkGroup(name.ifBlank { kind.uppercase() }, kind, entries)
        }
    }
}
