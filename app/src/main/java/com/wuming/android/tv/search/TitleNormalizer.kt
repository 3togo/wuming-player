package com.wuming.android.tv.search


/** Android runtime adapter for the platform-neutral title normalizer. */
object TitleNormalizer {
    private val CORE = com.wuming.tv.core.search.TitleNormalizer.chinese()

    @JvmStatic
    fun core(): com.wuming.tv.core.search.TitleNormalizer = CORE

    @JvmStatic
    fun clean(value: String): String = CORE.clean(value)

    @JvmStatic
    fun simplified(value: String): String = CORE.simplified(value)

    @JvmStatic
    fun traditional(value: String?): String = CORE.traditional(value)

    @JvmStatic
    fun comparisonText(value: String): String = CORE.comparisonText(value)

    @JvmStatic
    fun key(value: String): String = CORE.key(value)

    @JvmStatic
    fun equivalent(first: String, second: String): Boolean = CORE.equivalent(first, second)

    @JvmStatic
    fun containsHan(value: String): Boolean = CORE.containsHan(value)

    @JvmStatic
    fun containsLatin(value: String): Boolean = CORE.containsLatin(value)

    @JvmStatic
    fun scriptSegments(value: String): List<String> = CORE.scriptSegments(value)
}
