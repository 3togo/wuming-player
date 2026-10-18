package com.wuming.tv.core.runtime.guardspider

data class GuardedCatVodSiteSpec(
    val key: String,
    val name: String,
    val api: String,
    val extension: String = "",
    val categories: List<String> = emptyList(),
    val providerKey: String = key,
) {
    init {
        require(key.isNotBlank()) { "Guarded CatVod source key is required" }
        require(providerSimpleName.matches(Regex("[A-Za-z0-9_$]+"))) {
            "Guarded CatVod API must resolve to a simple class name"
        }
    }

    val providerSimpleName: String
        get() = api.removePrefix("csp_").removeSuffix("Guard")

    val className: String get() = "com.github.catvod.spider.$providerSimpleName"
}

data class GuardedCatVodHostRequest(
    val operation: String,
    val query: String? = null,
    val id: String? = null,
    val flag: String? = null,
    val page: String? = null,
    val extension: String? = null,
    val filters: Map<String, String> = emptyMap(),
)

internal data class GuardedCatVodHostResponse(
    val ok: Boolean = false,
    val result: String? = null,
    val error: String? = null,
)
