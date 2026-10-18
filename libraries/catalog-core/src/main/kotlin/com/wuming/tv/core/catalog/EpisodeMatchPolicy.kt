package com.wuming.tv.core.catalog

import java.util.Locale
import java.util.regex.Pattern

/** Episode identity heuristics shared by catalogue models and player workflows. */
object EpisodeMatchPolicy {
    private val EPISODE = Pattern.compile("(?i)(?:ep|第|e|[\\-\\.\\s])\\s?(\\d{1,4})")
    @JvmStatic
    fun number(text: String): Int = try {
        var cleaned = text.replace(Regex("\\[.*?\\]|\\(.*?\\)"), "")
        cleaned = cleaned.replace(Regex("\\b(19|20)\\d{2}\\b"), "")
        cleaned = cleaned.lowercase(Locale.ROOT)
            .replace(Regex("2160p|1080p|720p|480p|4k|h26[45]|x26[45]|mp4"), "")
        val matcher = EPISODE.matcher(cleaned)
        if (matcher.find()) Integer.parseInt(matcher.group(1).orEmpty())
        else cleaned.replace(Regex("\\D+"), "").let { if (it.isEmpty()) -1 else Integer.parseInt(it) }
    } catch (_: Exception) { -1 }

    @JvmStatic
    fun score(episodeName: String, episodeNumber: Int, requestedName: String, requestedNumber: Int): Int {
        if (episodeName.equals(requestedName, ignoreCase = true)) return 100
        if (requestedNumber != -1 && episodeNumber == requestedNumber) return 80
        val requested = requestedName.lowercase(Locale.ROOT)
        val episode = episodeName.lowercase(Locale.ROOT)
        if (requestedNumber == -1 && requestedName.length >= 2 && episode.contains(requested)) return 70
        if (requestedNumber == -1 && episodeName.length >= 2 && requested.contains(episode)) return 60
        return 0
    }
}
