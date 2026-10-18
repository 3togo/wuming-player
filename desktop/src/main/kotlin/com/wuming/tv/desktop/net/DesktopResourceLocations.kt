package com.wuming.tv.desktop.net

import com.wuming.tv.core.source.GitHubMirror
import java.net.URI

/** Prefer a canonical URL embedded in a mirror path, retaining the mirror as a fallback. */
internal fun embeddedHttpCandidates(value: String): List<String> {
    val input = value.trim()
    val direct = GitHubMirror.embedded(input) ?: return listOf(input)
    // When the outer URL is the configured mirror, prefer it: the embedded target is typically
    // unreachable in networks that require the proxy, so the outer (proxied) fetch must come first.
    return if (isConfiguredMirror(input)) listOf(input, direct).distinct()
    else listOf(direct, input).distinct()
}

/**
 * Resolve a (possibly relative) resource location against a configuration base URL, mirroring how the
 * Android app resolves `./lib/...` style paths. Absolute locations (a scheme or host is already
 * present) are returned unchanged; a null/blank base or a non-HTTP base also returns the location
 * unchanged so callers in a local-only context keep their previous behaviour.
 *
 * This is what lets `--test-live-source` (and any other desktop tool) load configs that reference
 * sibling resources with relative URLs such as `./lib/RTV.txt` instead of mis-resolving them as local
 * files under the working directory.
 */
internal fun resolveResourceLocation(location: String, configBase: String): String {
    val base = runCatching { URI(configBase) }.getOrNull() ?: return location
    if (base.scheme?.lowercase() !in setOf("http", "https") || base.host.isNullOrBlank()) return location
    val trimmed = location.trim()
    val uri = runCatching { URI(trimmed) }.getOrNull() ?: return location
    if (!uri.scheme.isNullOrBlank() || !uri.host.isNullOrBlank()) return location
    return base.resolve(trimmed).toString()
}

/**
 * Route GitHub content through the mirror this machine measured, using [GitHubMirror]'s shared rules so
 * the desktop and the Android app rewrite a URL the same way -- including dropping a mirror some other
 * network baked into a configuration.
 *
 * Controlled by the `tvbox.ghproxy` system property: set it to a different mirror host or prefix, or to
 * `off`, `none`, `false` or an empty value for direct access. Non-GitHub URLs are returned unchanged.
 *
 * The mirror is applied as a path prefix (`https://ghproxy.net/https://raw.githubusercontent.com/...`),
 * which is what these mirrors expect; it must be applied *after* relative-path resolution so the embedded
 * target stays a clean absolute URL (resolving against an already-proxied base would percent-encode the
 * inner `https://` and break the request).
 */
internal fun ghproxyUrl(url: String): String = GitHubMirror.resolve(url, configuredMirror())

private fun configuredMirror(): String {
    val setting = System.getProperty(PROPERTY) ?: return GitHubMirror.hostAt(GitHubMirror.DEFAULT_INDEX)
    return if (setting.isBlank() || setting.lowercase() in DISABLED) "" else setting
}

private fun isConfiguredMirror(url: String): Boolean {
    val host = runCatching { URI(url).host }.getOrNull() ?: return false
    val mirror = runCatching { URI(GitHubMirror.prefixOf(configuredMirror())).host }.getOrNull()
    return !mirror.isNullOrBlank() && host.equals(mirror, true)
}

private const val PROPERTY = "tvbox.ghproxy"

private val DISABLED = setOf("off", "none", "false")
