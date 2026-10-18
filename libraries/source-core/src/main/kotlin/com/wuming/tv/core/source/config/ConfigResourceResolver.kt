package com.wuming.tv.core.source.config

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.io.File
import java.net.URI

/** URI syntax belongs here; transports and package file lookup belong to hosts. */
object ConfigResourceResolver {
    @JvmStatic
    fun resolve(base: String, reference: String): String {
        if (reference.isBlank() || reference.startsWith("csp_") || reference.startsWith("builtin_")) return reference
        if (reference.contains("$$$")) return reference.split("$$$").joinToString("$$$") {
            if (isRelative(it)) resolve(base, it) else it
        }
        val suffixAt = listOf(";md5;", ";sha256;").map(reference::indexOf).filter { it >= 0 }.minOrNull()
        if (suffixAt != null) return resolve(base, reference.substring(0, suffixAt)) + reference.substring(suffixAt)
        if (Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(reference)) return reference
        val baseUri = if (Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(base)) URI(escape(base)) else File(base).absoluteFile.toURI()
        if (baseUri.scheme !in setOf("http", "https", "file", "assets")) return reference
        val embedded = baseUri.rawPath.orEmpty().removePrefix("/")
        if (baseUri.scheme in setOf("http", "https") && (embedded.startsWith("https://") || embedded.startsWith("http://"))) {
            return "${baseUri.scheme}://${baseUri.rawAuthority}/" + resolve(embedded, reference)
        }
        return baseUri.resolve(URI(escape(reference))).normalize().toASCIIString()
    }

    @JvmStatic
    fun isRelative(value: String): Boolean = value.startsWith("./") || value.startsWith("../")

    /** Preserve escapes and query grammar; encode illegal URI characters and Unicode once. */
    private fun escape(value: String): String = buildString {
        val allowed = "-._~:/?#[]@!$&'()*+,;="
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length && value.substring(i + 1, i + 3).all { it in "0123456789abcdefABCDEF" }) {
                append(value.substring(i, i + 3)); i += 3; continue
            }
            val cp = value.codePointAt(i)
            if (cp < 128 && (c.isLetterOrDigit() || c in allowed)) append(c)
            else String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).forEach { append("%%%02X".format(it.toInt() and 255)) }
            i += Character.charCount(cp)
        }
    }

    /** Only configuration resource fields are traversed. Provider code/text is not globally replaced. */
    fun rewrite(root: JsonObject, resolver: (String) -> String): JsonObject = root.deepCopy().also { copy ->
        fun relative(value: String): String = if (value.contains("$$$")) value.split("$$$").joinToString("$$$") {
            if (isRelative(it)) resolver(it) else it
        } else if (isRelative(value)) resolver(value) else value
        fun nested(node: JsonElement): JsonElement = when {
            node.isJsonObject -> node.asJsonObject.deepCopy().also { obj -> obj.entrySet().toList().forEach { (k, v) -> obj.add(k, nested(v)) } }
            node.isJsonArray -> com.google.gson.JsonArray().also { arr -> node.asJsonArray.forEach { arr.add(nested(it)) } }
            node.isJsonPrimitive && node.asJsonPrimitive.isString -> JsonPrimitive(relative(node.asString))
            else -> node.deepCopy()
        }
        fun fields(obj: JsonObject, names: List<String>) = names.forEach { key -> obj.get(key)?.let { obj.add(key, nested(it)) } }
        fields(copy, listOf("spider", "wallpaper", "logo", "danmaku"))
        listOf("sites" to listOf("api", "jar", "ext"), "lives" to listOf("url", "epg", "logo"), "urls" to listOf("url")).forEach { (array, names) ->
            copy.get(array)?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { if (it.isJsonObject) fields(it.asJsonObject, names) }
        }
    }
}
