package com.wuming.tv.core.catalog

/** A transport/interstitial response that must not be reported as a provider JSON parser bug. */
enum class CatalogAccessFailureKind {
    WAF_ACCESS_DENIED,
    BROWSER_CHALLENGE,
}

data class CatalogAccessFailure(
    val kind: CatalogAccessFailureKind,
    val message: String,
)

class CatalogAccessException(val failure: CatalogAccessFailure) :
    IllegalArgumentException(failure.message)

/**
 * Recognises bounded, platform-independent access pages returned in place of CatVod JSON.
 *
 * This deliberately identifies only strong interstitial signatures. It neither bypasses an
 * upstream access policy nor turns arbitrary HTML into a provider-specific workaround.
 */
object CatalogAccessFailureClassifier {

    fun classify(payload: String): CatalogAccessFailure? {
        val sample = payload.trim().take(MAX_SAMPLE).lowercase()
        if (!looksLikeHtml(sample)) return null

        return when {
            isWafDenial(sample) -> CatalogAccessFailure(
                CatalogAccessFailureKind.WAF_ACCESS_DENIED,
                "Catalog source access was denied by an upstream web application firewall.",
            )
            isBrowserChallenge(sample) -> CatalogAccessFailure(
                CatalogAccessFailureKind.BROWSER_CHALLENGE,
                "Catalog source returned an interactive browser verification page.",
            )
            else -> null
        }
    }

    private fun looksLikeHtml(value: String): Boolean =
        value.startsWith("<!doctype html") ||
            value.startsWith("<html") ||
            "<head" in value ||
            "<body" in value

    private fun isWafDenial(value: String): Boolean =
        "safeline waf" in value ||
            "/.safeline/" in value ||
            ("access forbidden" in value && ("waf" in value || "attack detected" in value))

    private fun isBrowserChallenge(value: String): Boolean =
        "verifying your browser" in value ||
            "enable javascript and cookies" in value ||
            ("just a moment" in value && "challenge" in value) ||
            "cf-mitigated" in value

    private const val MAX_SAMPLE = 64 * 1024
}
