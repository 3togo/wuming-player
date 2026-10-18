package com.wuming.android.tv.utils

object Bilibili {
    private const val QUALITY_ANONYMOUS = "32"
    private const val QUALITY_LOGGED_IN = "80"
    private const val FORMAT_MPD = "mpd"

    /** Supply the provider's defaults when related-video IDs omit quality and format. */
    @JvmStatic fun normalizeEpisodeUrl(
        url: String?,
        loggedIn: Boolean,
    ): String {
        if (url.isNullOrEmpty()) return ""
        val parts =
            java.util.regex.Pattern
                .compile("\\+")
                .split(url, -1)
        if (parts.size != 4 || parts[2].isNotEmpty() || parts[3].isNotEmpty()) return url
        if (!digits(parts[0]) || !digits(parts[1])) return url
        return parts[0] + "+" + parts[1] + "+" + (if (loggedIn) QUALITY_LOGGED_IN else QUALITY_ANONYMOUS) + "+" + FORMAT_MPD
    }

    private fun digits(value: String): Boolean = value.isNotEmpty() && value.all { Character.isDigit(it) }
}
