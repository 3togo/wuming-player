package com.wuming.tv.core.source.config

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wuming.tv.core.base.native.NativeLoader
import com.wuming.tv.core.source.RecordAdmission
import com.wuming.tv.core.source.TvBoxConfigPayloadDecoder
import com.wuming.tv.core.source.TvBoxRecordFields
import com.wuming.tv.core.source.TvBoxSourceRuntime
import com.wuming.tv.core.source.TvBoxSourceRuntimePolicy

private val SITES: RecordAdmission<TvBoxSiteConfig> = RecordAdmission.of(
    { site: TvBoxSiteConfig -> site.key },
    { site: TvBoxSiteConfig -> TvBoxRecordFields.siteRejection(site.key, site.api) },
)

private val LIVES: RecordAdmission<TvBoxLiveConfig> = RecordAdmission.of(
    { live: TvBoxLiveConfig -> live.name },
    { live: TvBoxLiveConfig -> TvBoxRecordFields.liveRejection(live.url) },
)

private val RULES: RecordAdmission<TvBoxAdRuleConfig> = RecordAdmission.of({ rule: TvBoxAdRuleConfig -> rule.name })

/**
 * Decodes the TVBox configuration JSON document into the platform-agnostic
 * [TvBoxConfig] model.
 *
 * This is the single source of truth for the TVBox config schema. The Android
 * app and the desktop JVM client both delegate their config parsing here so the
 * site/model extraction, type classification and spider-fallback detection stay
 * consistent across platforms.
 *
 * Parsing is intentionally JSON-only (Gson) with no Android or desktop specific
 * dependencies, which keeps the class usable from `source-core`.
 */
object TvBoxConfigDecoder {

    private val nativeAvailable: Boolean get() = NativeLoader.available

    @JvmStatic
    fun decode(json: JsonObject): TvBoxConfig {
        val sites = SITES.admitJson(json, "sites") { obj -> site(obj) }
        val lives = LIVES.admitJson(json, "lives") { obj -> live(obj) }
        val adRules = RULES.admitJson(json, "rules") { obj -> adRule(obj) }
        val spider = stringOrNull(json, "spider")?.trim()?.takeIf { it.isNotBlank() }
        val raw = json.asRawMap()
        return TvBoxConfig(
            sites = sites.kept,
            lives = lives.kept,
            adRules = adRules.kept,
            spider = spider,
            raw = raw,
            admissions = listOf(sites, lives, adRules),
        )
    }

    @JvmStatic
    fun decode(payload: String): TvBoxConfig {
        val jsonString = TvBoxConfigPayloadDecoder.decode(payload.toByteArray())
        if (nativeAvailable) {
            val resultJson = nativeDecode(jsonString)
            if (resultJson.isNotEmpty()) {
                val config = TvBoxConfigNativeJson.fromJson(resultJson)
                if (config != null) return config.toTvBoxConfig()
            }
        }
        val json = JsonParser.parseString(jsonString).asJsonObject
        return decode(json)
    }

    // ------------------------------------------------------------------
    // Site
    // ------------------------------------------------------------------

    fun site(obj: JsonObject): TvBoxSiteConfig {
        val type = intOr(obj, "type", 0)
        val api = stringOr(obj, "api").orEmpty()
        val ext = extString(obj)
        val jar = stringOr(obj, "jar").orEmpty()
        val key = stringOr(obj, "key").orEmpty()
        val name = stringOr(obj, "name").orEmpty()
        val click = stringOr(obj, "click").orEmpty()
        val playUrl = stringOr(obj, "playUrl").orEmpty()
        val searchable = intOr(obj, "searchable", 1)
        val quickSearch = intOr(obj, "quickSearch", 1)
        val changeable = intOr(obj, "changeable", 0)
        val timeout = intOr(obj, "timeout", 0)
        val indexs = intOr(obj, "indexs", 0).let { if (it == 0) "" else "1" }
        val hide = booleanOr(obj, "hide", false)
        val categories = strings(obj, "categories").filter(String::isNotBlank).distinct()
        val header = objOrNull(obj, "header")?.asStringMap().orEmpty()
        val runtime = TvBoxSourceRuntimePolicy.classify(type, api)
        val fallback = parseFallback(runtime, api, ext, jar)
        return TvBoxSiteConfig(
            key = key,
            name = name,
            type = type,
            api = api,
            ext = ext,
            jar = jar,
            click = click,
            playUrl = playUrl,
            searchable = searchable,
            quickSearch = quickSearch,
            changeable = changeable,
            hide = hide,
            indexs = indexs,
            timeout = timeout,
            categories = categories,
            header = header,
            runtime = runtime,
            fallbackSource = fallback,
        )
    }

    internal fun parseFallback(
        runtime: TvBoxSourceRuntime,
        api: String,
        ext: String,
        jar: String,
    ): TvBoxFallbackSource? = when {
        runtime == TvBoxSourceRuntime.DIRECT_JAVASCRIPT && (api.startsWith("http") || ext.startsWith("http")) ->
            TvBoxFallbackSource(
                kind = TvBoxSourceRuntime.DIRECT_JAVASCRIPT,
                ext = ext.ifBlank { api }.takeIf { it.isNotBlank() },
                downloadUrl = if (api.startsWith("http")) api else null,
                raw = api.takeIf { it.startsWith("http") },
            )
        runtime == TvBoxSourceRuntime.SPIDER_RUNTIME_REQUIRED && (jar.isNotBlank() || ext.isNotBlank()) ->
            TvBoxFallbackSource(
                kind = TvBoxSourceRuntime.SPIDER_RUNTIME_REQUIRED,
                jar = jar.ifBlank { ext }.takeIf { it.isNotBlank() },
                downloadUrl = if (api.startsWith("http")) api else null,
                raw = api.takeIf { it.startsWith("http") },
            )
        else -> null
    }

    // ------------------------------------------------------------------
    // Live / Rules
    // ------------------------------------------------------------------

    fun live(obj: JsonObject): TvBoxLiveConfig = TvBoxLiveConfig(
        name = stringOr(obj, "name").orEmpty(),
        url = stringOr(obj, "url").orEmpty(),
        type = intOr(obj, "type", 0),
        epg = stringOr(obj, "epg").orEmpty(),
        logo = stringOr(obj, "logo").orEmpty(),
    )

    fun adRule(obj: JsonObject): TvBoxAdRuleConfig = TvBoxAdRuleConfig(
        name = stringOr(obj, "name").orEmpty(),
        hosts = arrayOrNull(obj, "hosts").orEmptyArray().mapNotNull { it.stringOrNull() },
        regex = arrayOrNull(obj, "regex").orEmptyArray().mapNotNull { it.stringOrNull() },
    )

    // ------------------------------------------------------------------
    // JsonObject helpers (previously re-implemented inside the desktop
    // configuration loader; now shared across both platforms)
    // ------------------------------------------------------------------

    internal fun stringOr(root: JsonObject, name: String): String =
        root.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()

    internal fun stringOrNull(root: JsonObject, name: String): String? =
        root.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    internal fun booleanOr(root: JsonObject, name: String, default: Boolean): Boolean =
        root.get(name)?.takeIf { it.isJsonPrimitive }?.let { node ->
            val p = node.asJsonPrimitive
            when {
                p.isBoolean -> p.asBoolean
                p.isNumber -> p.asInt != 0
                p.isString -> p.asString.toBooleanStrictOrNull() ?: (p.asString == "1")
                else -> default
            }
        } ?: default

    internal fun intOr(root: JsonObject, name: String, fallback: Int): Int = runCatching {
        root.get(name)?.takeIf { it.isJsonPrimitive }?.asString?.toInt() ?: fallback
    }.getOrDefault(fallback)

    internal fun objOrNull(root: JsonObject, name: String): JsonObject? =
        root.get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    internal fun arrayOrNull(root: JsonObject, name: String): JsonArray? =
        root.get(name)?.takeIf { it.isJsonArray }?.asJsonArray

    internal fun strings(root: JsonObject, name: String): List<String> = runCatching {
        root.get(name)?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty()
            .mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString?.trim() }
    }.getOrDefault(emptyList())

    internal fun extString(root: JsonObject): String {
        val node = root.get("ext") ?: return ""
        return if (node.isJsonPrimitive && node.asJsonPrimitive.isString) {
            node.asString
        } else {
            node.toString()
        }
    }

    private fun JsonArray?.orEmptyArray(): JsonArray = this ?: JsonArray()

    internal fun JsonElement.stringOrNull(): String? =
        takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    internal fun JsonObject.asStringMap(): Map<String, String> =
        entrySet().associateTo(LinkedHashMap()) { (k, v) -> k to (v.stringOrNull() ?: "") }

    internal fun JsonObject.asRawMap(): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        for ((k, v) in entrySet()) result[k] = v.toRaw()
        return result
    }

    internal fun JsonElement.toRaw(): Any? = when {
        isJsonNull -> null
        isJsonPrimitive -> {
            val p = asJsonPrimitive
            when {
                p.isString -> p.asString
                p.isBoolean -> p.asBoolean
                p.isNumber -> p.asNumber
                else -> p.asString
            }
        }
        isJsonArray -> asJsonArray.map { it.toRaw() }
        isJsonObject -> asJsonObject.asRawMap()
        else -> null
    }

    @JvmStatic
    private external fun nativeDecode(json: String): String
}

private data class TvBoxConfigNativeJson(
    val sites: List<TvBoxSiteConfigNativeJson> = emptyList(),
    val lives: List<TvBoxLiveConfigNativeJson> = emptyList(),
    val ad_rules: List<TvBoxAdRuleConfigNativeJson> = emptyList(),
    val spider: String = "",
) {
    fun toTvBoxConfig(): TvBoxConfig {
        // The native decoder has already flattened the document, so a section it left out and a
        // section that was there but empty both arrive as an empty list. The rules still apply.
        val sites = SITES.admit("sites", this.sites.map { it.toTvBoxSiteConfig() })
        val lives = LIVES.admit("lives", this.lives.map { it.toTvBoxLiveConfig() })
        val adRules = RULES.admit("rules", this.ad_rules.map { it.toTvBoxAdRuleConfig() })
        return TvBoxConfig(
            sites = sites.kept,
            lives = lives.kept,
            adRules = adRules.kept,
            spider = spider.trim().takeIf { it.isNotBlank() },
            raw = emptyMap(),
            admissions = listOf(sites, lives, adRules),
        )
    }

    companion object {
        fun fromJson(json: String): TvBoxConfigNativeJson? {
            return try {
                com.google.gson.Gson().fromJson(json, TvBoxConfigNativeJson::class.java)
            } catch (e: Exception) { null }
        }
    }
}

private data class TvBoxSiteConfigNativeJson(
    val key: String = "",
    val name: String = "",
    val type_id: Int = 0,
    val api: String = "",
    val ext: String = "",
    val jar: String = "",
    val click: String = "",
    val play_url: String = "",
    val searchable: Int = 1,
    val quick_search: Int = 1,
    val changeable: Int = 0,
    val hide: Boolean = false,
    val indexs: String = "",
    val timeout: Int = 0,
    val categories: List<String> = emptyList(),
    val header: Map<String, String> = emptyMap(),
    val runtime: String = "",
    val fallback_kind: String = "",
    val fallback_ext: String = "",
    val fallback_download_url: String = "",
    val fallback_raw: String = "",
) {
    fun toTvBoxSiteConfig(): TvBoxSiteConfig {
        val runtimeEnum = TvBoxSourceRuntime.valueOf(runtime)
        val fallback = if (fallback_kind.isNotEmpty()) {
            TvBoxFallbackSource(
                kind = TvBoxSourceRuntime.valueOf(fallback_kind),
                ext = fallback_ext.takeIf { it.isNotBlank() },
                jar = fallback_ext.takeIf { it.isNotBlank() && fallback_kind == "SPIDER_RUNTIME_REQUIRED" },
                downloadUrl = fallback_download_url.takeIf { it.isNotBlank() },
                raw = fallback_raw.takeIf { it.isNotBlank() },
            )
        } else null
        return TvBoxSiteConfig(
            key = key,
            name = name,
            type = type_id,
            api = api,
            ext = ext,
            jar = jar,
            click = click,
            playUrl = play_url,
            searchable = searchable,
            quickSearch = quick_search,
            changeable = changeable,
            hide = hide,
            indexs = indexs,
            timeout = timeout,
            categories = categories.filter(String::isNotBlank).distinct(),
            header = header,
            runtime = runtimeEnum,
            fallbackSource = fallback,
        )
    }
}

private data class TvBoxLiveConfigNativeJson(
    val name: String = "",
    val url: String = "",
    val type_id: Int = 0,
    val epg: String = "",
    val logo: String = "",
) {
    fun toTvBoxLiveConfig(): TvBoxLiveConfig = TvBoxLiveConfig(name, url, type_id, epg, logo)
}

private data class TvBoxAdRuleConfigNativeJson(
    val name: String = "",
    val hosts: List<String> = emptyList(),
    val regex: List<String> = emptyList(),
) {
    fun toTvBoxAdRuleConfig(): TvBoxAdRuleConfig = TvBoxAdRuleConfig(name, hosts, regex)
}
