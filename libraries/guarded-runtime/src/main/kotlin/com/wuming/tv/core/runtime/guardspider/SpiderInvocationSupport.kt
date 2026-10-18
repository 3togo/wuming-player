package com.wuming.tv.core.runtime.guardspider

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Locale

/**
 * Shared spider invocation support logic used by both [GuardedCatVodHostMain]
 * (desktop JVM) and [SpiderHelperService] (Android device).
 *
 * All methods here are pure utilities that do not depend on any platform-specific
 * or module-specific API. The only method that differs between the two environments
 * (`attachPlaybackCookies`, which calls `Spider.cookieHeader()`) remains in each
 * module but delegates to [playbackUrl] from this class.
 */
object SpiderInvocationSupport {

    val GSON: Gson = Gson()

    fun playbackUrl(root: JsonObject): String {
        val value = root.get("url")
        var selected = ""
        if (value != null && value.isJsonPrimitive) {
            selected = value.asString
        } else if (value != null && value.isJsonArray) {
            for (candidate in value.asJsonArray) {
                if (candidate.isJsonPrimitive && isHttp(candidate.asString)) {
                    selected = candidate.asString
                    break
                }
            }
        } else if (value != null && value.isJsonObject) {
            val obj = value.asJsonObject
            val values: JsonArray = if (obj.has("values") && obj.get("values").isJsonArray)
                obj.getAsJsonArray("values") else JsonArray()
            val position = if (obj.has("position")) obj.get("position").asInt else 0
            if (position >= 0 && position < values.size()) selected = optionUrl(values.get(position))
            if (!isHttp(selected)) {
                for (candidate in values) {
                    selected = optionUrl(candidate)
                    if (isHttp(selected)) break
                }
            }
        }
        val prefix = if (root.has("playUrl") && root.get("playUrl").isJsonPrimitive)
            root.get("playUrl").asString else ""
        val result = prefix + selected
        return if (isHttp(result)) result else ""
    }

    fun optionUrl(value: JsonElement): String {
        if (!value.isJsonObject) return ""
        val location = value.asJsonObject.get("v")
        return if (location != null && location.isJsonPrimitive) location.asString else ""
    }

    fun isHttp(value: String?): Boolean =
        value != null && (value.startsWith("http://") || value.startsWith("https://"))

    @JvmStatic
    fun setSiteKey(spider: Any, siteKey: String) {
        try {
            spider.javaClass.getField("siteKey").set(spider, siteKey)
        } catch (e: ReflectiveOperationException) {
            // Legacy providers are permitted to omit this optional field.
        }
    }

    @JvmStatic
    fun required(spider: Any, name: String, vararg parameters: Class<*>): Method {
        val method = find(spider, name, *parameters)
            ?: throw IllegalArgumentException("Provider does not implement $name")
        return method
    }

    @JvmStatic
    fun findCloudPlayer(spider: Any, flag: String?, address: String?): Any? {
        val fieldName = cloudPlayerField(value(flag) + "\n" + value(address)) ?: return null
        val field = findField(spider.javaClass, fieldName) ?: return null
        if (Modifier.isStatic(field.modifiers)) return null
        return try {
            field.isAccessible = true
            field.get(spider)
        } catch (e: IllegalAccessException) {
            null
        }
    }

    @JvmStatic
    fun cloudPlayerField(value: String): String? {
        val normalized = value.lowercase(Locale.ROOT)
        if (normalized.contains("pan.quark.cn") || normalized.contains("夸克") || normalized.contains("夸父")) return "yq"
        if (normalized.contains("drive.uc.cn") || normalized.contains("优汐") || normalized.contains("uc盘")
            || normalized.contains("uc原") || normalized.contains("uc智")) return "N"
        if (normalized.contains("alipan.com") || normalized.contains("aliyundrive.com")
            || normalized.contains("阿里") || normalized.contains("阿狸")) return "SN"
        return null
    }

    @JvmStatic
    fun findField(type: Class<*>, name: String): Field? {
        var current: Class<*>? = type
        while (current != null) {
            try {
                return current.getDeclaredField(name)
            } catch (e: NoSuchFieldException) {
                // Continue through provider base classes.
            }
            current = current.superclass
        }
        return null
    }

    @JvmStatic
    fun find(spider: Any, name: String, vararg parameters: Class<*>): Method? {
        return try {
            spider.javaClass.getMethod(name, *parameters)
        } catch (e: NoSuchMethodException) {
            null
        }
    }

    @JvmStatic
    fun findStatic(type: Class<*>, name: String, vararg parameters: Class<*>): Method? {
        return try {
            type.getMethod(name, *parameters)
        } catch (e: NoSuchMethodException) {
            null
        }
    }

    @JvmStatic
    fun rootCause(failure: Throwable): Throwable {
        var current: Throwable = if (failure is InvocationTargetException && failure.targetException != null)
            failure.targetException else failure
        while (current.cause != null && current.cause !== current) current = current.cause!!
        return current
    }

    @JvmStatic
    fun value(value: Any?): String = value?.toString() ?: ""

    @JvmStatic
    fun value(value: String?): String = value ?: ""

    @JvmStatic
    fun value(value: String?, fallback: String): String = if (value.isNullOrBlank()) fallback else value

    /**
     * Attaches playback cookies to a spider playerContent result.
     *
     * The `cookieHeaderProvider` function supplies the Cookie header for a given URL,
     * allowing each environment (desktop JVM vs Android device) to use its own
     * `Spider.cookieHeader()` implementation.
     */
    @JvmStatic
    fun attachPlaybackCookies(
        result: String,
        cookieHeaderProvider: (String) -> String
    ): String {
        return try {
            val root = JsonParser.parseString(result).asJsonObject
            val location = playbackUrl(root)
            if (location.isEmpty()) return result
            val cookie = cookieHeaderProvider(location)
            if (cookie.isEmpty()) return result
            val original = root.get("header")
            val encoded = original != null && original.isJsonPrimitive
            val headers: JsonObject = when {
                original == null -> JsonObject()
                encoded -> JsonParser.parseString(original.asString).asJsonObject
                else -> original.asJsonObject
            }
            headers.addProperty("Cookie", cookie)
            if (encoded) root.addProperty("header", GSON.toJson(headers))
            else root.add("header", headers)
            GSON.toJson(root)
        } catch (e: Exception) {
            result
        }
    }
}
