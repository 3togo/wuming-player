package com.wuming.tv.core.source.config

import com.wuming.tv.core.source.SectionAdmission
import com.wuming.tv.core.source.TvBoxSourceRuntime

/**
 * Platform-agnostic representation of the TVBox configuration document.
 *
 * This model intentionally mirrors the canonical TVBox config schema (the same
 * JSON shape that the Android [com.wuming.android.tv.bean.Site] / `Config`
 * entities parse). Keeping the parsing here means both the Android app and the
 * desktop JVM client decode the config with identical semantics, instead of
 * each re-implementing the JSON shape (site type classification, spider
 * fallback detection, flag/live/rule extraction).
 */
data class TvBoxConfig(
    val sites: List<TvBoxSiteConfig> = emptyList(),
    val lives: List<TvBoxLiveConfig> = emptyList(),
    val adRules: List<TvBoxAdRuleConfig> = emptyList(),
    val spider: String? = null,
    val raw: Map<String, Any?> = emptyMap(),
    /**
     * One verdict per record read from [sites], [lives] and [adRules], so a client can report what
     * a config contained instead of only what it ended up using.
     */
    val admissions: List<SectionAdmission<*>> = emptyList(),
)

/**
 * A single content source (site) entry of a TVBox config.
 *
 * The [runtime] is derived from the parsed [type]/[api] using the shared
 * [com.wuming.tv.core.source.TvBoxSourceRuntimePolicy] so both platforms
 * classify sources identically.
 */
data class TvBoxSiteConfig(
    val key: String = "",
    val name: String = "",
    val type: Int = 0,
    val api: String = "",
    val ext: String = "",
    val jar: String = "",
    val click: String = "",
    val playUrl: String = "",
    val searchable: Int = 1,
    val quickSearch: Int = 1,
    val changeable: Int = 0,
    val hide: Boolean = false,
    val indexs: String = "",
    val timeout: Int = 0,
    val categories: List<String> = emptyList(),
    val header: Map<String, String> = emptyMap(),
    val runtime: TvBoxSourceRuntime = TvBoxSourceRuntime.UNSUPPORTED_TYPE,
    val fallbackSource: TvBoxFallbackSource? = null,
) {
    /** Push sources accept external input rather than exposing a browsable catalogue. */
    val inputOnly: Boolean get() = key == "push_agent" || api.removePrefix("csp_").removeSuffix("Guard") == "Push"
    // searchable=0 and absent indexs still allow Home/categories (music and education).
    // Using those flags here silently left valid desktop sites empty and unactivated.
    val browsable: Boolean get() = !hide && !inputOnly
}

/**
 * Discovered fallback when [TvBoxSiteConfig.api] points at an external
 * spider file (`.js` / `.jar`) instead of an inline `csp_*` / native source.
 */
data class TvBoxFallbackSource(
    val kind: TvBoxSourceRuntime,
    val ext: String? = null,
    val jar: String? = null,
    val downloadUrl: String? = null,
    val raw: String? = null,
)

data class TvBoxLiveConfig(
    val name: String = "",
    val url: String = "",
    val type: Int = 0,
    val epg: String = "",
    val logo: String = "",
)

/**
 * HLS ad-skip rule (TVBox `rules` array entry).
 */
data class TvBoxAdRuleConfig(
    val name: String = "",
    val hosts: List<String> = emptyList(),
    val regex: List<String> = emptyList(),
)
