package com.wuming.tv.core.media.subtitle

import java.util.Locale

/** Player-independent preferences. The host supplies its locale; manual selections stay in the player. */
object SubtitleSelectionPolicy {
    @JvmStatic
    fun preferredLanguages(locale: Locale): List<String> {
        val language = locale.language
        if (language.isBlank() || language == "und") return emptyList()
        return buildList {
            add(locale.toLanguageTag())
            if (language == "zh") add("zh-${chineseScript(locale)}")
            add(language)
        }.distinct()
    }

    @JvmStatic
    fun score(locale: Locale, tag: String?): Int {
        val track = Locale.forLanguageTag(tag.orEmpty().trim().replace('_', '-'))
        if (track.language.isBlank() || track.language != locale.language) return 0
        if (track.toLanguageTag().equals(locale.toLanguageTag(), true)) return 400
        if (locale.language == "zh") {
            if (track.toLanguageTag() == track.language) return 200
            return if (chineseScript(track) == chineseScript(locale)) 300 else 100
        }
        return if (track.toLanguageTag().startsWith(locale.toLanguageTag() + "-", true) ||
            locale.toLanguageTag().startsWith(track.toLanguageTag() + "-", true)) 300 else 200
    }

    /** Flags use the stable default/forced/autoselect bit values, without a player dependency. */
    @JvmStatic
    fun selectionFlags(locale: Locale, languages: List<String>, rawFlags: List<Int>): List<Int> {
        require(languages.size == rawFlags.size)
        if (languages.size <= 1) return rawFlags.map { if (it == 0) 1 else it }
        if (rawFlags.any { it != 0 }) return rawFlags.map { if (it == 0) 4 else it }
        val best = languages.indices.maxByOrNull { score(locale, languages[it]) } ?: return emptyList()
        return languages.indices.map { if (it == best) 1 else 4 }
    }

    private fun chineseScript(locale: Locale): String = when {
        locale.script.isNotBlank() -> locale.script
        locale.country in setOf("TW", "HK", "MO") -> "Hant"
        else -> "Hans"
    }
}
