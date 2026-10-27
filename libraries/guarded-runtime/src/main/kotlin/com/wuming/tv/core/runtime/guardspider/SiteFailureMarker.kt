package com.wuming.tv.core.runtime.guardspider

/**
 * Names the site-side cause behind an empty guarded provider response.
 *
 * A guarded spider is adapted to one site. When that site adds an anti-bot check or changes its
 * markup, the spider's own parser throws (for example `org.json.JSONException: No value for froms`)
 * and the host returns an empty body. The host diagnostics then carry an obfuscated stack trace,
 * which is fine for a log but useless in the UI. Classifying the cause lets callers show one
 * actionable line and report repeated failures once per cause instead of once per attempt.
 */
object SiteFailureMarker {
    fun classify(diagnostics: List<String>): String? {
        if (diagnostics.isEmpty()) return null
        val text = diagnostics.joinToString("\n")
        return when {
            BROWSER.any { text.contains(it, ignoreCase = true) } -> BROWSER_REASON
            PARSER.any { text.contains(it, ignoreCase = true) } -> PARSER_REASON
            NETWORK.any { text.contains(it, ignoreCase = true) } -> NETWORK_REASON
            else -> null
        }
    }

    const val BROWSER_REASON = "the site now requires browser verification (anti-bot check)"
    const val PARSER_REASON = "the source's site adapter no longer matches the site layout"
    const val NETWORK_REASON = "the site did not answer in time"

    private val BROWSER = listOf(
        "browser verification", "403 Forbidden", "just a moment", "verify you are human", "cf-browser-verification",
    )
    private val PARSER = listOf("JSONException", "No value for", "End of input", "IndexOutOfBoundsException")
    private val NETWORK = listOf("SocketTimeoutException", "ConnectException", "UnknownHostException", "timed out")
}
