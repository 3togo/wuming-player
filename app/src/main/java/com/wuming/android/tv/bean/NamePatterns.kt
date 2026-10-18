package com.wuming.android.tv.bean

import java.util.regex.Pattern

/** Compatibility facade keeping video and episode cleanup patterns shared. */
internal object NamePatterns {
    @JvmField val CJK: String = com.wuming.tv.core.catalog.NamePatterns.CJK
    @JvmField val SIZE_PREFIX: Pattern = com.wuming.tv.core.catalog.NamePatterns.SIZE_PREFIX
    @JvmField val EMPTY_BOOK: Pattern = com.wuming.tv.core.catalog.NamePatterns.EMPTY_BOOK
    @JvmField val GROUP_TAIL: Pattern = com.wuming.tv.core.catalog.NamePatterns.GROUP_TAIL

    @JvmStatic
    fun compileBoundary(vararg regexes: String): Array<Pattern> = com.wuming.tv.core.catalog.NamePatterns.compileBoundary(*regexes)

    @JvmStatic
    fun containsAny(value: CharSequence, vararg chars: Char): Boolean = com.wuming.tv.core.catalog.NamePatterns.containsAny(value, *chars)
}
