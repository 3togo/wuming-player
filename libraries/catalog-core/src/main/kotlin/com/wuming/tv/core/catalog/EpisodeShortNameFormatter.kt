package com.wuming.tv.core.catalog

object EpisodeShortNameFormatter {

    @JvmStatic
    fun formatWithVod(original: String, vodName: String): String {
        if (vodName.isEmpty()) return EpisodeDisplayName.format(original)
        val vShort = VodDisplayName.format(vodName)
        val matchesOriginal = indexOfIgnoringSpace(original, vodName) >= 0
        val matchesShort = vShort != vodName && indexOfIgnoringSpace(original, vShort) >= 0
        if (!matchesOriginal && !matchesShort) return EpisodeDisplayName.format(original)
        val vodNormalized = if (vShort.isEmpty()) vodName else vShort
        val result = EpisodeDisplayName.format(replaceVodPart(original, vodNormalized))
        if (result.equals(vShort, ignoreCase = true) || result.equals(vodNormalized, ignoreCase = true)) return vShort
        return result
    }

    @JvmStatic
    fun replaceVodPart(episode: String, vodNormalized: String): String {
        val start = indexOfIgnoringSpace(episode, vodNormalized)
        if (start < 0) return episode
        val end = matchEndIgnoringSpace(episode, start, vodNormalized)
        return episode.substring(0, start) + vodNormalized + episode.substring(end)
    }

    @JvmStatic
    fun indexOfIgnoringSpace(haystack: String, needle: String): Int {
        val required = nonSpaceLength(needle)
        if (required == 0 || required > nonSpaceLength(haystack)) return -1
        var start = 0
        while (start <= haystack.length - required) {
            val end = matchEndIgnoringSpace(haystack, start, needle)
            if (end >= 0) {
                var s = start
                while (s < end && Character.isWhitespace(haystack[s])) s++
                return s
            }
            start++
        }
        return -1
    }

    @JvmStatic
    fun matchEndIgnoringSpace(haystack: String, start: Int, needle: String): Int {
        var i = start
        var j = 0
        val n = haystack.length
        val m = needle.length
        while (true) {
            while (j < m && Character.isWhitespace(needle[j])) j++
            if (j == m) return i
            while (i < n && Character.isWhitespace(haystack[i])) i++
            if (i == n) return -1
            if (Character.toLowerCase(haystack[i]) != Character.toLowerCase(needle[j])) return -1
            i++
            j++
        }
    }

    fun nonSpaceLength(value: String): Int {
        var count = 0
        for (i in 0 until value.length) {
            if (!Character.isWhitespace(value[i])) count++
        }
        return count
    }
}
