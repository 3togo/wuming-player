package com.wuming.tv.core.library

/** Parses persisted content keys without depending on an Android database constant. */
object ContentKeyCodec {
    const val DEFAULT_DELIMITER = "@@@"

    @JvmStatic
    @JvmOverloads
    fun sourceKey(key: String, delimiter: String = DEFAULT_DELIMITER): String {
        val index = key.indexOf(delimiter)
        return if (index < 0) key else key.substring(0, index)
    }

    @JvmStatic
    @JvmOverloads
    fun contentId(key: String, delimiter: String = DEFAULT_DELIMITER): String {
        val index = key.indexOf(delimiter)
        return if (index < 0) key else key.substring(index + delimiter.length)
    }

    /**
     * History keys may end in a catalog id. Strip it only when the suffix is unambiguous;
     * delimiters that are part of the content id are otherwise preserved.
     */
    @JvmStatic
    @JvmOverloads
    fun historyContentId(
        key: String,
        catalogId: Int,
        delimiter: String = DEFAULT_DELIMITER,
    ): String {
        val start = key.indexOf(delimiter)
        if (start < 0) return key
        val contentStart = start + delimiter.length
        val suffixStart = key.lastIndexOf(delimiter)
        val contentEnd = if (
            suffixStart > contentStart &&
            key.substring(suffixStart + delimiter.length) == catalogId.toString()
        ) suffixStart else key.length
        return key.substring(contentStart, contentEnd)
    }
}
