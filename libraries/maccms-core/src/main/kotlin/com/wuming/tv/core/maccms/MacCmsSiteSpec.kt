package com.wuming.tv.core.maccms

/**
 * One TVBox `type` 0 (MacCMS XML) or `type` 1 (MacCMS JSON) collection site.
 *
 * These sources are plain HTTP GET endpoints returning `vod_*` payloads, so unlike
 * spiders they execute no third-party code and need no user approval.
 */
data class MacCmsSiteSpec(
    val key: String,
    val name: String,
    val api: String,
    /** Android `Site.type`: 0 = XML, 1 = JSON. Anything else is rejected by the caller. */
    val type: Int = 1,
    val ext: String = "",
    val headers: Map<String, String> = emptyMap(),
    val categories: List<String> = emptyList(),
) {
    val xml: Boolean get() = type == 0

    /** `ext` can carry a site token, so it must never reach a log or a UI label. */
    override fun toString(): String =
        "MacCmsSiteSpec(key=$key, name=$name, api=$api, type=$type, ext=<redacted>, " +
            "headers=${headers.keys.sorted()}, categories=$categories)"
}
