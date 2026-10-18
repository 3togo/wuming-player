package com.wuming.tv.core.source

import java.net.URI

/**
 * GitHub accelerator mirrors -- the `https://<mirror>/<github url>` prefixes that make GitHub
 * reachable from networks that cannot see it.
 *
 * Which mirror to use is a property of *this* user's network, never of a configuration file: a
 * config that arrives already wrapped in someone else's mirror is unwrapped and re-routed through
 * the mirror this app selected, or fetched direct when the user asked for none. Android applies
 * this while signing outgoing requests and the desktop client applies it while resolving resource
 * locations, so the rules live here instead of in either port.
 */
object GitHubMirror {

    /**
     * The mirrors a user may choose, index 0 meaning "no mirror". The Settings screens label these
     * positions with the localized `github_proxy_name` array, so the two must stay the same length.
     */
    @JvmField
    val HOSTS: List<String> = listOf(
        "",
        "ghproxy.net",
        "gh-proxy.com",
        "v6.gh-proxy.org",
        "gh.idayer.com",
        "gh-proxy.org",
        "v4.gh-proxy.org",
        "cdn.gh-proxy.org",
        "axisnow.gh-proxy.org",
    )

    /** Without a choice on record, GitHub goes through the first mirror rather than direct. */
    const val DEFAULT_INDEX = 1

    /** Where a device remembers the mirror host it picked. */
    const val HOST_KEY = "github_proxy_host"

    /** The superseded way of remembering the choice: an index into a list that could change underneath it. */
    const val LEGACY_INDEX_KEY = "github_proxy"

    private val GITHUB_HOST = Regex("^(?:[^/]*\\.)?github(?:usercontent)?\\.com$")

    /** A mirror link carries its target as its own path: `https://mirror/https://raw.githubusercontent.com/…`. */
    private val EMBEDDED_URL = Regex("^https?://[^/]+/(https?://.*)$")

    /**
     * Paths a mirror must not sit in front of. Playlists, segments and media are fetched by the
     * player, which needs ranges, seeking and its own timeouts; a mirror in between breaks playback
     * in ways that look like a dead source.
     */
    private val NEVER_MIRRORED = listOf(
        ".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv", ".webm", ".m4v",
        ".mp3", ".m4a", ".aac", ".flac", ".wav", ".ogg", ".opus",
        ".m3u8", ".m3u", ".ts", ".m2ts", ".mts",
        ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".ico",
    )

    /** The mirror host at a settings index; an out-of-range index falls back to the default. */
    @JvmStatic
    fun hostAt(index: Int): String = HOSTS.getOrNull(index) ?: HOSTS[DEFAULT_INDEX]

    /**
     * The mirror a device picked: the stored host, or -- for a device that still holds only the
     * superseded index -- the host that index meant in [HOSTS]. An empty stored host means the user
     * chose direct access and is kept as such.
     */
    @JvmStatic
    fun hostOf(storedHost: String?, storedIndex: Int): String = storedHost ?: hostAt(storedIndex)

    /** Where a host sits in [HOSTS], or -1 when it is not one of the mirrors this app ships. */
    @JvmStatic
    fun hostIndexOf(host: String?): Int = HOSTS.indexOfFirst { it == host?.trim() }

    /** The request prefix of a mirror host, or empty when the host means "fetch direct". */
    @JvmStatic
    fun prefixOf(host: String): String {
        val value = host.trim().removeSuffix("/")
        if (value.isEmpty()) return ""
        return if (value.startsWith("http://") || value.startsWith("https://")) value else "https://$value"
    }

    /** Whether [url] points at GitHub content that a mirror can serve. */
    @JvmStatic
    fun isGitHubUrl(url: String): Boolean = host(url)?.matches(GITHUB_HOST) == true

    /**
     * The publisher's own URL hidden inside a mirror link, whatever mirror the config author picked.
     * Used to drop accelerator links from collected sources: they route every later user through an
     * unrelated third party chosen by someone else. A link whose hidden target is not a fetchable
     * address of its own is not a mirror link and reports none.
     */
    @JvmStatic
    fun embedded(url: String): String? {
        val target = EMBEDDED_URL.matchEntire(url.trim())?.groupValues?.get(1) ?: return null
        return if (uri(target) != null) target else null
    }

    /**
     * The URL as published, with any mirror prefix removed. `null` when the address is not something
     * a mirror serves, so callers leave it alone.
     */
    @JvmStatic
    fun canonical(url: String): String? {
        val value = url.trim()
        if (isGitHubUrl(value)) return value
        val target = embedded(value) ?: return null
        return if (isGitHubUrl(target)) target else null
    }

    /**
     * Where [url] should actually be fetched from: the published address, prefixed with [mirrorHost] --
     * which may be a bare host or a full prefix -- so a mirror someone else baked into the URL never
     * survives, and an empty [mirrorHost] means direct access even for a URL that arrived already
     * proxied.
     */
    @JvmStatic
    fun resolve(url: String, mirrorHost: String): String {
        val value = url.trim()
        val target = canonical(value) ?: return value
        val prefix = prefixOf(mirrorHost)
        if (prefix.isEmpty()) return target
        if (value.startsWith("$prefix/")) return value
        return "$prefix/$target"
    }

    /** Whether a mirror must stay out of this request's way. */
    @JvmStatic
    fun isNeverMirrored(url: String): Boolean {
        val path = hostAndPath(url)?.lowercase() ?: return false
        if (NEVER_MIRRORED.any { path.endsWith(it) }) return true
        // Some publishers pass the file as a query parameter, and the mirror would be handed a
        // media URL it cannot stream either.
        val query = query(url)?.lowercase() ?: return false
        return query.split('&', ';').any { pair ->
            val value = pair.substringAfter('=', "")
            NEVER_MIRRORED.any { value.endsWith(it) }
        }
    }

    private fun host(url: String): String? = uri(url)?.host?.lowercase()

    private fun hostAndPath(url: String): String? = uri(url)?.let { "${it.host.orEmpty()}${it.rawPath.orEmpty()}" }

    private fun query(url: String): String? = uri(url)?.rawQuery

    private fun uri(url: String): URI? = runCatching { URI(url.trim()) }
        .getOrNull()
        ?.takeIf { it.scheme?.lowercase() in HTTP_SCHEMES && !it.host.isNullOrBlank() && it.userInfo == null }

    private val HTTP_SCHEMES = setOf("http", "https")
}
