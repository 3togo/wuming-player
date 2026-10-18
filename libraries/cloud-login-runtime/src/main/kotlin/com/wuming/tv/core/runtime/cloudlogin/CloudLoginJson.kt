package com.wuming.tv.core.runtime.cloudlogin

import com.google.gson.JsonObject

internal fun JsonObject.objectValue(name: String): JsonObject =
    get(name)?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()

internal fun JsonObject.stringValue(name: String): String = runCatching {
    get(name)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
}.getOrDefault("")

internal fun JsonObject.longValue(name: String): Long = runCatching {
    get(name)?.asLong ?: 0L
}.getOrDefault(0L)

internal fun JsonObject.booleanValue(name: String): Boolean = runCatching {
    get(name)?.asBoolean == true
}.getOrDefault(false)

internal fun cookiePairs(headers: List<String>): Map<String, String> = buildMap {
    headers.forEach { header ->
        val pair = header.substringBefore(';').trim()
        val split = pair.indexOf('=')
        if (split in 1 until pair.lastIndex) put(pair.substring(0, split), pair.substring(split + 1))
    }
}

internal fun parseCookieHeader(value: String): LinkedHashMap<String, String> = linkedMapOf<String, String>().apply {
    value.split(';').forEach { pair ->
        val split = pair.indexOf('=')
        if (split > 0) {
            val name = pair.substring(0, split).trim()
            val content = pair.substring(split + 1).trim()
            if (name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}")) && content.length <= 4096) {
                put(name, content)
            }
        }
    }
}

internal fun Map<String, String>.cookieHeader(): String = entries.joinToString("; ") { "${it.key}=${it.value}" }
