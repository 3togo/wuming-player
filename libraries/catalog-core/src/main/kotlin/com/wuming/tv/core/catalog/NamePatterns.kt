package com.wuming.tv.core.catalog

import java.util.regex.Pattern

/**
 * Shared building blocks for the release-name shorteners
 * (episode and video display names).
 *
 * Kept in one place so the two vocabularies cannot drift apart on the tokens they
 * have in common: every cloud-drive spider emits the same "`[2.65GB]…`"
 * preamble, empty 《》 book-title and "-GroupName" suffix.
 */
object NamePatterns {

    @JvmField
    val CJK = "\\u4e00-\\u9fff"

    /** Leading size badge the drive spiders prepend: "[2.65GB]" / "3.45 GB." */
    @JvmField
    val SIZE_PREFIX = Pattern.compile(
        "(?i)^[\\[\\(（【]?\\s*[\\d.]+\\s*(?:KB|MB|GB|TB)[\\]\\)）】]?\\s*[.\\-_]?\\s*")

    /** "《》" carries no title information once the tech tokens are gone. */
    @JvmField
    val EMPTY_BOOK = Pattern.compile("《\\s*》")

    /** Trailing release group: "-微博影音臻享". */
    @JvmField
    val GROUP_TAIL = Pattern.compile("-[\\w@$CJK．]{2,}\\s*$")

    /**
     * Compiles each token with latin-alphanumeric boundaries. Bracket characters,
     * punctuation and whitespace all act as separators, so "hc" never eats a slice of
     * a word while still matching inside "[hc]".
     */
    @JvmStatic
    fun compileBoundary(vararg regexes: String): Array<Pattern> {
        return Array(regexes.size) { i ->
            Pattern.compile("(?i)(?<![A-Za-z0-9])" + regexes[i] + "(?![A-Za-z0-9])")
        }
    }

    @JvmStatic
    fun containsAny(value: CharSequence, vararg chars: Char): Boolean {
        for (i in 0 until value.length) {
            val c = value[i]
            for (candidate in chars) {
                if (c == candidate) return true
            }
        }
        return false
    }
}
