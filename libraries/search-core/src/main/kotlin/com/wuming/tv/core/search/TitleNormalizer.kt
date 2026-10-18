package com.wuming.tv.core.search

import com.wuming.tv.core.base.native.NativeLoader
import java.text.Normalizer
import java.util.Locale

class TitleNormalizer(
    converter: TitleScriptConverter?,
    private val traditionalConverter: TitleScriptConverter,
) {
    constructor(converter: TitleScriptConverter?) : this(converter, TitleScriptConverter { it })

    private val converter = converter ?: TitleScriptConverter { it }
    private val useNative = nativeAvailable && converter === IDENTITY_CONVERTER

    fun clean(value: String?): String {
        if (value == null) return ""
        if (useNative) return nativeClean(value)
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .trim()
        val result = StringBuilder(normalized.length)
        var pendingSpace = false
        var offset = 0
        while (offset < normalized.length) {
            val codePoint = normalized.codePointAt(offset)
            offset += Character.charCount(codePoint)
            if (Character.isLetterOrDigit(codePoint) || Character.isIdeographic(codePoint)) {
                if (pendingSpace && result.isNotEmpty()) result.append(' ')
                result.appendCodePoint(codePoint)
                pendingSpace = false
            } else {
                pendingSpace = true
            }
        }
        return result.toString()
    }

    fun simplified(value: String?): String {
        return converter.simplify(normalizeUnicode(value))
    }

    fun traditional(value: String?): String = traditionalConverter.simplify(normalizeUnicode(value))

    private fun normalizeUnicode(value: String?): String =
        Normalizer.normalize(value ?: "", Normalizer.Form.NFKC).trim()

    fun comparisonText(value: String?): String {
        if (useNative) return nativeComparisonText(value ?: "")
        return clean(simplified(value))
    }

    fun key(value: String?): String {
        if (useNative) return nativeKey(value ?: "")
        return comparisonText(value).replace(" ", "")
    }

    fun equivalent(first: String?, second: String?): Boolean {
        val firstKey = key(first)
        return firstKey.isNotEmpty() && firstKey == key(second)
    }

    fun containsHan(value: String?): Boolean = containsScript(value, Character.UnicodeScript.HAN)

    fun containsLatin(value: String?): Boolean = containsScript(value, Character.UnicodeScript.LATIN)

    fun scriptSegments(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).trim()
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var currentScript = Script.OTHER
        var offset = 0
        while (offset < normalized.length) {
            val codePoint = normalized.codePointAt(offset)
            offset += Character.charCount(codePoint)
            val script = classify(codePoint)
            if (script == Script.OTHER) {
                addSegment(result, current, currentScript)
                currentScript = Script.OTHER
                continue
            }
            if (script == Script.DIGIT && currentScript != Script.OTHER) {
                current.appendCodePoint(codePoint)
                continue
            }
            if (script != currentScript) {
                addSegment(result, current, currentScript)
                currentScript = script
            }
            current.appendCodePoint(codePoint)
        }
        addSegment(result, current, currentScript)
        return result
    }

    private fun containsScript(value: String?, expected: Character.UnicodeScript): Boolean {
        if (value == null) return false
        var offset = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            offset += Character.charCount(codePoint)
            if (Character.UnicodeScript.of(codePoint) == expected) return true
        }
        return false
    }

    private fun classify(codePoint: Int): Script {
        if (Character.isDigit(codePoint)) return Script.DIGIT
        return when (Character.UnicodeScript.of(codePoint)) {
            Character.UnicodeScript.HAN -> Script.HAN
            Character.UnicodeScript.LATIN -> Script.LATIN
            else -> Script.OTHER
        }
    }

    private fun addSegment(result: MutableList<String>, current: StringBuilder, script: Script) {
        if (current.isEmpty()) return
        val segment = current.toString().trim()
        current.setLength(0)
        val minimum = if (script == Script.HAN) 2 else 3
        if (segment.codePointCount(0, segment.length) >= minimum) result.add(segment)
    }

    private enum class Script { HAN, LATIN, DIGIT, OTHER }

    companion object {
        @JvmStatic
        fun chinese(): TitleNormalizer = TitleNormalizer(
            { com.wuming.tv.core.text.ChineseScripts.t2s(false, it) },
            { com.wuming.tv.core.text.ChineseScripts.s2t(false, it) })
        private val IDENTITY_CONVERTER = TitleScriptConverter { it }
        private val nativeAvailable: Boolean get() = NativeLoader.available

        @JvmStatic
        fun identity(): TitleNormalizer = TitleNormalizer(IDENTITY_CONVERTER)

        @JvmStatic
        private external fun nativeClean(value: String): String

        @JvmStatic
        private external fun nativeKey(value: String): String

        @JvmStatic
        private external fun nativeComparisonText(value: String): String
    }
}
