package com.wuming.tv.core.playback

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wuming.tv.core.base.HttpTokens
import java.net.URI
import java.util.Collections
import java.util.Locale

/** TVBox JSON parser dialect, independent of HTTP transport and player capabilities. */
object JsonPlaybackResponse {
    enum class Failure { INVALID_JSON, INVALID_URL }
    class DecodeException(val failure: Failure, cause: Throwable? = null) :
        IllegalArgumentException("Invalid playback parser response: ${failure.name}", cause)
    data class Result(val url: String, val headers: Map<String, String>)

    private val schemes = setOf("http", "https", "rtsp", "rtmp", "rtmps", "file", "content", "proxy", "udp", "rtp", "smb", "ftp")

    @JvmStatic @JvmOverloads
    fun decode(json: String, fallback: Map<String, String> = emptyMap(), endpoint: String = "", allowedSchemes: Set<String>? = schemes): Result {
        val root = try { JsonParser.parseString(json).asJsonObject }
        catch (error: RuntimeException) { throw DecodeException(Failure.INVALID_JSON, error) }
        val data = root.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
        val raw = text(root.get("url")).ifEmpty { text(data?.get("url")) }
        val url = resolveUrl(raw, endpoint)
        if (!isMediaUrl(url, allowedSchemes)) throw DecodeException(Failure.INVALID_URL)
        return Result(url, Collections.unmodifiableMap(headers(root, fallback)))
    }

    @JvmStatic fun resolveUrl(value: String, endpoint: String): String =
        if (value.startsWith("//")) {
            (if (endpoint.startsWith("http:", true)) "http:" else "https:") + value
        } else value.trim()

    /**
     * Admission is structural; the player still decides transport support. A host may provide its
     * supported schemes, or null to preserve an existing extensible native-protocol boundary.
     * Executable/document schemes are always rejected.
     */
    @JvmStatic @JvmOverloads
    fun isMediaUrl(value: String?, allowedSchemes: Set<String>? = schemes): Boolean {
        if (value.isNullOrBlank() || value.any(Character::isISOControl)) return false
        return runCatching {
            // Android's concatenated-segment transport is interpreted later by MediaSourceFactory.
            val resource = if (value.contains("***") && value.contains("|||")) value.substringBefore("|||") else value
            val uri = URI(resource.trim())
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            scheme != null && scheme !in setOf("javascript", "vbscript", "data", "about", "intent") &&
                (allowedSchemes == null || scheme in allowedSchemes) && !uri.rawSchemeSpecificPart.isNullOrBlank() &&
                (scheme !in setOf("http", "https", "rtsp", "rtmp", "rtmps", "ftp") || !uri.rawAuthority.isNullOrBlank())
        }.getOrDefault(false)
    }

    /** Fallback < nested data < root; aliases override case-insensitively without dropping auth. */
    @JvmStatic @JvmOverloads
    fun headers(root: JsonObject, fallback: Map<String, String> = emptyMap()): MutableMap<String, String> {
        val result = linkedMapOf<String, String>()
        fallback.forEach { (name, value) -> put(result, name, value) }
        root.get("data")?.takeIf { it.isJsonObject }?.asJsonObject?.let { add(result, it) }
        add(result, root)
        return result
    }

    private fun add(result: MutableMap<String, String>, obj: JsonObject) {
        val header = obj.get("header")
        val decoded = if (header?.isJsonPrimitive == true) runCatching { JsonParser.parseString(header.asString) }.getOrNull() else header
        decoded?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.forEach { (name, value) ->
            put(result, name, headerText(value))
        }
        obj.entrySet().forEach { (name, value) ->
            if (name.lowercase(Locale.ROOT) in setOf("ua", "user-agent", "referer", "cookie", "origin")) put(result, name, headerText(value))
        }
    }

    private fun put(result: MutableMap<String, String>, name: String, value: String) {
        if (!HttpTokens.isToken(name) || !HttpTokens.isHeaderValue(value)) return
        val key = when (name.lowercase(Locale.ROOT)) {
            "ua", "user-agent" -> "User-Agent"
            "referer" -> "Referer"
            "cookie" -> "Cookie"
            "origin" -> "Origin"
            else -> name
        }
        result.keys.removeAll { it.equals(key, true) }
        result[key] = value
    }

    // Existing adapters stringify all JSON primitives in header maps, but URLs must be strings.
    private fun headerText(value: JsonElement?): String =
        value?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    private fun text(value: JsonElement?): String =
        value?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.trim().orEmpty()
}
