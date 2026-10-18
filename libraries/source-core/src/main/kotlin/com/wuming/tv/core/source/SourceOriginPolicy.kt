package com.wuming.tv.core.source

import com.wuming.tv.core.source.config.TvBoxConfig

/**
 * Checks the site a source plays from, rather than the endpoint that served its
 * configuration. A CMS config stays reachable long after the domain it points at
 * has expired and been re-registered as a domain-sale page.
 *
 * Every verdict here is advisory. A probe can fail for reasons the user does not
 * share (regional routing, rate limits, DNS), so callers label the source instead
 * of removing it.
 */
object SourceOriginPolicy {

    /** Config fields that name the origin site, most specific first. */
    @JvmField
    val ORIGIN_FIELDS: List<String> = listOf("主页url", "homeUrl", "url", "主页", "host", "siteUrl")

    /** Sources served by the app's own local server are never network-probed. */
    @JvmField
    val LOCAL_HOSTS: List<String> = listOf("127.0.0.1", "localhost", "0.0.0.0", "::1")

    /**
     * Text a domain-parking page shows in its first kilobytes. Deliberately narrow:
     * a live site whose headline happens to contain one of these phrases is mislabelled,
     * so match only on the page opening and keep the phrases unambiguous.
     */
    @JvmField
    val PARKING_MARKERS: List<String> = listOf(
        "for sale",
        "forsale",
        "domain may be",
        "buy this domain",
        "domain is parked",
        "abovedomains",
        "域名正在出售",
        "该域名已过期",
    )

    /** How many characters of a fetched page are inspected for parking text. */
    const val PARKING_SCAN_LIMIT = 4000

    enum class Verdict {
        /** Nothing in the config names an origin, so there is nothing to check. */
        NO_ORIGIN,

        /** The origin is served locally by the app. */
        LOCAL,

        /** The origin answered. */
        ALIVE,

        /** The origin did not answer within the caller's timeout. */
        UNREACHABLE,

        /** The origin answers but shows a domain-parking page. */
        PARKED,
    }

    /**
     * The first value in [config] that names a remote origin. A field may hold a
     * URL followed by notes, so only its leading token is used.
     */
    @JvmStatic
    fun originOf(config: TvBoxConfig): String? = ORIGIN_FIELDS
        .asSequence()
        .mapNotNull { config.raw[it] as? String }
        .map { it.trim().substringBefore(' ') }
        .firstOrNull { isHttp(it) }

    /** Whether [url] points at the app's own process instead of a remote site. */
    @JvmStatic
    fun isLocal(url: String): Boolean {
        val authority = url.substringAfter("://", url).substringBefore('/').substringBefore(':')
        return LOCAL_HOSTS.any { it.equals(authority, ignoreCase = true) }
    }

    /**
     * Classifies an origin probe. [body] is the beginning of the response and may be
     * absent when the caller only measured reachability.
     */
    @JvmStatic
    fun verdict(url: String?, reachable: Boolean, body: String? = null): Verdict = when {
        url == null -> Verdict.NO_ORIGIN
        isLocal(url) -> Verdict.LOCAL
        !reachable -> Verdict.UNREACHABLE
        body != null && isParked(body) -> Verdict.PARKED
        else -> Verdict.ALIVE
    }

    /** True when the page opening reads as a parked or expired domain. */
    @JvmStatic
    fun isParked(body: String): Boolean {
        val head = body.take(PARKING_SCAN_LIMIT).lowercase()
        return PARKING_MARKERS.any { head.contains(it.lowercase()) }
    }

    private fun isHttp(value: String): Boolean =
        value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true)
}
