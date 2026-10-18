package com.wuming.android.tv.utils

import java.util.ArrayList
import java.util.Arrays
import java.util.LinkedHashSet
import java.util.regex.Matcher
import java.util.regex.Pattern

open class PartUtil {
    companion object {
        private val PATTERN_SEASON = Pattern.compile("^(.*?)第")
        private val PATTERN_YEAR = Pattern.compile("^(.*)(19|20)\\d{2}$")

        @JvmStatic fun split(text: String): List<String> {
            val items = LinkedHashSet<String>()
            val rawSplits =
                when {
                    text.contains("：") -> Pattern.compile("：").split(text)
                    text.contains("第") && text.contains("季") -> extractSeason(text)
                    text.contains("(") -> arrayOf(Pattern.compile(Pattern.quote("(")).split(text)[0])
                    text.contains(" ") -> Pattern.compile(" ").split(text)
                    else -> extractYear(text)
                }
            items.add(text)
            for (raw in rawSplits) {
                val trimmed = raw.trim { it <= ' ' }
                val part = if (trimmed.contains(" ")) Pattern.compile(" ").split(trimmed)[0].trim { it <= ' ' } else trimmed
                if (part.isNotEmpty()) items.add(part)
            }
            return ArrayList(items)
        }

        private fun extractSeason(text: String): Array<String> {
            val matcher = PATTERN_SEASON.matcher(text)
            if (matcher.find()) {
                val s = matcher.group(1)!!.trim { it <= ' ' }
                if (s.isNotEmpty()) return arrayOf(s)
            }
            return Pattern
                .compile("第")
                .split(text)
                .filter { it.isNotEmpty() && !it.contains("季") }
                .toTypedArray()
        }

        private fun extractYear(text: String): Array<String> {
            val matcher = PATTERN_YEAR.matcher(text)
            if (matcher.find()) {
                val s = matcher.group(1)!!.trim { it <= ' ' }
                if (s.isNotEmpty()) return arrayOf(s)
            }
            return emptyArray()
        }
    }
}
