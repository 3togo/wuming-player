package com.wuming.tv.core.cloud

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import java.util.regex.Pattern

/** Strict parser for provider metadata emitted as CatVod episode URLs. */
object ExternalEpisodePayload {
    const val MAX_LENGTH = 64 * 1024
    private const val MAX_VALUE_LENGTH = 4096

    @JvmStatic
    fun json(payload: String?, decodeBase64: Boolean): JsonObject? {
        if (payload.isNullOrBlank() || payload.length > MAX_LENGTH) return null
        val direct = parseObject(payload.trim())
        if (direct != null || !decodeBase64) return direct
        return try {
            var normalized = payload.trim().replace('-', '+').replace('_', '/')
            normalized += "=".repeat((4 - normalized.length % 4) % 4)
            val bytes = Base64.getDecoder().decode(normalized)
            if (bytes.size > MAX_LENGTH) null else parseObject(String(bytes, StandardCharsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    @JvmStatic
    fun belongsTo(flag: String?, payload: JsonObject?, vararg markers: String): Boolean {
        val source = StringBuilder(flag ?: "")
        if (payload != null) {
            for (key in arrayOf("flag", "provider", "drive", "source", "pan")) {
                val value = first(payload, key)
                if (value.isNotBlank()) source.append('\n').append(value)
            }
        }
        val value = source.toString().lowercase(Locale.ROOT)
        return markers.any { value.contains(it.lowercase(Locale.ROOT)) }
    }

    /** Returns the provider explicitly named by a CatVod route label, if any. */
    @JvmStatic
    fun labeledProviderId(flag: String?): String? {
        val value = flag?.lowercase(Locale.ROOT).orEmpty()
        return when {
            contains(value, "alipan", "aliyun", "阿里") -> "alipan"
            contains(value, "quark", "夸克", "夸父") -> "quark"
            contains(value, "ucpan", "uc盘", "uc网", "uc云", "uc原", "uc智", "优汐") -> "uc"
            contains(value, "baidu", "百度", "bd原", "bd预") -> "baidu"
            contains(value, "pan123", "123pan", "123盘", "123网", "123云") -> "123pan"
            contains(value, "tianyi", "天翼", "天意", "189云", "189盘") -> "tianyi"
            else -> null
        }
    }

    private fun contains(value: String, vararg markers: String): Boolean =
        markers.any { value.contains(it.lowercase(Locale.ROOT)) }

    @JvmStatic
    fun first(objectValue: JsonObject?, vararg keys: String): String {
        if (objectValue == null) return ""
        for (key in keys) {
            try {
                val value: JsonElement = objectValue.get(key) ?: continue
                if (value.isJsonNull || !value.isJsonPrimitive) continue
                val text = value.asString
                if (text.isNotBlank() && text.length <= MAX_VALUE_LENGTH && !hasControl(text)) return text
            } catch (_: Exception) {
            }
        }
        return ""
    }

    @JvmStatic
    fun number(objectValue: JsonObject?, vararg keys: String): Long =
        first(objectValue, *keys).toLongOrNull() ?: -1L

    @JvmStatic
    fun identifier(value: String?): Boolean =
        value != null && value.length in 1..256 && IDENTIFIER.matches(value)

    @JvmStatic
    fun capability(value: String?): Boolean =
        value != null && value.isNotBlank() && value.length <= MAX_VALUE_LENGTH && !hasControl(value)

    @JvmStatic
    fun split(payload: String?, delimiter: String, minimumParts: Int): Array<String>? {
        if (payload.isNullOrBlank() || payload.length > MAX_LENGTH) return null
        val values = Pattern.compile(delimiter).split(payload, -1)
        return values.takeIf { it.size in minimumParts..16 }
    }

    private fun parseObject(value: String): JsonObject? {
        if (!value.startsWith("{")) return null
        return try {
            JsonParser.parseString(value).takeIf(JsonElement::isJsonObject)?.asJsonObject
        } catch (_: Exception) {
            null
        }
    }

    private fun hasControl(value: String): Boolean = value.any(Character::isISOControl)

    private val IDENTIFIER = Regex("[A-Za-z0-9_-]+")
}
