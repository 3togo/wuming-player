package com.wuming.tv.core.catalog

import com.google.gson.JsonParser

data class CatalogImageRequest(
    val location: String,
    val headers: Map<String, String> = emptyMap(),
)

/** Decodes the inline image-header convention used by Android TVBox poster loading. */
object CatalogImageRequestParser {

    @JvmStatic
    fun parse(value: String): CatalogImageRequest {
        val input = value.trim()
        if (input.isEmpty()) return CatalogImageRequest("")
        val marker = input.indexOf('@')
        if (marker < 0) return CatalogImageRequest(normalizeLocation(input))
        val location = normalizeLocation(input.substring(0, marker))
        val headers = linkedMapOf<String, String>()
        var position = marker
        while (position < input.length) {
            val next = input.indexOf('@', position + 1)
            val segment = input.substring(position + 1, if (next < 0) input.length else next)
            val separator = segment.indexOf('=')
            if (separator > 0) {
                val key = segment.substring(0, separator)
                val content = segment.substring(separator + 1)
                when (key.lowercase()) {
                    "cookie" -> add(headers, "Cookie", content)
                    "referer" -> add(headers, "Referer", content)
                    "user-agent" -> add(headers, "User-Agent", content)
                    "headers" -> parseHeaders(content).forEach { (name, header) -> add(headers, name, header) }
                }
            }
            if (next < 0) break
            position = next
        }
        return CatalogImageRequest(location, headers)
    }

    private fun normalizeLocation(value: String): String = value.trim().let {
        if (it.startsWith("//")) "https:$it" else it
    }

    private fun parseHeaders(value: String): Map<String, String> = runCatching {
        JsonParser.parseString(value).asJsonObject.entrySet().mapNotNull { (key, item) ->
            item.takeIf { it.isJsonPrimitive }?.asString?.let { key to it }
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun add(headers: MutableMap<String, String>, name: String, value: String) {
        val key = name.trim()
        val content = value.trim()
        if (key.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}")) &&
            content.isNotEmpty() && content.length <= 8192 && content.none(Character::isISOControl)
        ) headers[key] = content
    }
}
