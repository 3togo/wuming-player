package com.wuming.tv.core.source

/**
 * Canonical contract for the desktop companion HTTP API (see docs/desktop-companion-api.md).
 * The endpoint paths and input rules live here so the Android server and the desktop client cannot
 * drift: a change to the protocol is a single, reviewed edit in this shared core module.
 */
object CompanionProtocol {
    const val ROOT: String = "/desktop/v1/"
    const val PAIR: String = "/pair"
    const val SOURCES: String = ROOT + "sources"
    const val SEARCH: String = ROOT + "search"
    const val DETAIL: String = ROOT + "detail"
    const val PLAY: String = ROOT + "play"
    const val MAX_PARAMETER_LENGTH: Int = 200

    /** 256-bit pairing token, hex encoded. */
    const val TOKEN_PATTERN: String = "[0-9a-f]{64}"

    /** Applies the server's canonical required-parameter normalization and safety checks. */
    @JvmStatic
    fun requireParameter(value: String?, name: String): String {
        val normalized = value?.trim().orEmpty()
        require(normalized.isNotEmpty()) { "$name is required" }
        require(normalized.length <= MAX_PARAMETER_LENGTH) { "$name is too long" }
        require(normalized.none(Character::isISOControl)) { "$name contains control characters" }
        return normalized
    }

    /** Validates the `source` parameter shared by search/detail/play. */
    @JvmStatic
    fun validateSource(source: String) {
        requireParameter(source, "source")
    }

    /** Validates the `query` parameter for search. */
    @JvmStatic
    fun validateQuery(query: String) {
        requireParameter(query, "query")
    }
}
