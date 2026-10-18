package com.wuming.tv.core.playback

/** Platform-neutral representation of TVBox's host-scoped HLS purification rules. */
data class HlsAdRule @JvmOverloads constructor(
    val name: String = "",
    val hosts: List<String> = emptyList(),
    val patterns: List<String> = emptyList(),
)

object HlsAdFilterPolicy {
    /** Preserve Android's first nonempty matching group and rule order on every platform. */
    @JvmStatic
    fun firstMatchingPatterns(location: String, rules: List<HlsAdRule>): List<String> =
        rules.firstOrNull { rule -> rule.patterns.isNotEmpty() && rule.hosts.any(location::contains) }
            ?.patterns ?: emptyList()

    /** Matches Android's host containment behavior while deduplicating remote rule patterns. */
    @JvmStatic
    fun matchingPatterns(location: String, rules: List<HlsAdRule>): List<String> = rules.asSequence()
        .filter { rule -> rule.hosts.any { host -> host.isNotBlank() && location.contains(host, ignoreCase = true) } }
        .flatMap { it.patterns.asSequence() }
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .toList()
}
