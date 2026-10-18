package com.wuming.tv.core.runtime.jvmspider

internal data class JvmSpiderHostRequest(
    val operation: String,
    val extension: String? = null,
    val query: String? = null,
    val id: String? = null,
    val flag: String? = null,
    val page: String? = null,
    val filters: Map<String, String> = emptyMap(),
)

internal data class JvmSpiderHostResponse(
    val ok: Boolean = false,
    val result: String? = null,
    val error: String? = null,
)
