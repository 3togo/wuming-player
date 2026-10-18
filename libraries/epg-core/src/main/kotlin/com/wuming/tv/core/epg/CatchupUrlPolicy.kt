package com.wuming.tv.core.epg

import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.regex.Pattern

data class CatchupTemplate @JvmOverloads constructor(
    val type: String = "",
    val source: String = "",
    val matchPattern: String = "",
    val replacement: String = "",
)

object CatchupUrlPolicy {

    private val tokenPattern = Pattern.compile("(\\$?\\{[^}]*})")
    private val tagPattern = Pattern.compile("\\{([^}]+)}")

    @JvmStatic
    fun matches(template: CatchupTemplate, url: String): Boolean {
        if (template.matchPattern.isEmpty()) return false
        return url.contains(template.matchPattern) || Pattern.compile(template.matchPattern).matcher(url).find()
    }

    @JvmStatic
    fun format(
        template: CatchupTemplate,
        baseUrl: String,
        startTimeMs: Long,
        endTimeMs: Long,
        zoneId: ZoneId,
        locale: Locale,
    ): String {
        var expanded = template.source
        val matcher = tokenPattern.matcher(expanded)
        val tokens = mutableListOf<String>()
        while (matcher.find()) tokens += matcher.group()
        for (token in tokens) {
            expanded = expanded.replace(token, expandToken(token, startTimeMs, endTimeMs, zoneId, locale))
        }
        if (template.type == "default") return expanded

        var url = baseUrl
        val replacement = template.replacement.split(',', limit = 2)
        if (replacement.size == 2) {
            url = Pattern.compile(replacement[0]).matcher(url).replaceAll(replacement[1])
        }
        if (!URI.create(url).query.isNullOrEmpty()) expanded = expanded.replace("?", "&")
        return url + expanded
    }

    private fun expandToken(
        token: String,
        startTimeMs: Long,
        endTimeMs: Long,
        zoneId: ZoneId,
        locale: Locale,
    ): String {
        val matcher = tagPattern.matcher(token)
        if (!matcher.find()) return ""
        val tag = matcher.group(1)
        val parenthesis = tag.indexOf(')')
        if (tag.startsWith("(b") && parenthesis >= 0) {
            return formatTime(startTimeMs, tag.substring(parenthesis + 1), zoneId, locale)
        }
        if (tag.startsWith("(e") && parenthesis >= 0) {
            return formatTime(endTimeMs, tag.substring(parenthesis + 1), zoneId, locale)
        }
        if (tag.startsWith("utcend:")) return (endTimeMs / 1000).toString()
        if (tag.startsWith("utc:")) return (startTimeMs / 1000).toString()
        return ""
    }

    private fun formatTime(timeMs: Long, format: String, zoneId: ZoneId, locale: Locale): String {
        if (format == "timestamp") return (timeMs / 1000).toString()
        return DateTimeFormatter.ofPattern(format, locale)
            .format(Instant.ofEpochMilli(timeMs).atZone(zoneId))
    }
}
