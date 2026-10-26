package com.wuming.tv.core.search

import com.google.gson.JsonParser

/** Suggestions are explicit user choices; they never broaden an automatic search request. */
object SearchSuggestions {
    private const val LIMIT = 20
    private val separators = Regex("[\\s\\p{P}\\p{S}]+")
    private val chineseSeason = Regex("^(.+?)\\s*第([0-9零〇一二两兩三四五六七八九十]+)([季部])$")
    private val englishSeason = Regex("^(.+?)\\s+(season\\s+|s)([0-9]{1,2})$", RegexOption.IGNORE_CASE)
    private const val DIGITS = "零一二三四五六七八九"

    @JvmStatic
    fun local(query: String?): List<String> {
        val title = query?.trim().orEmpty()
        if (title.isEmpty()) return emptyList()
        val words = linkedSetOf(title)
        val chinese = chineseSeason.matchEntire(title)
        val english = englishSeason.matchEntire(title)
        if (chinese != null) {
            val (base, number, unit) = chinese.destructured
            words.add(base.trim())
            val season = seasonNumber(number)
            if (season != null && season in 1..98) {
                val next = if (number.all(Char::isDigit)) (season + 1).toString() else chineseNumber(season + 1)
                words.add("${base.trim()}第$next$unit")
            }
        } else if (english != null) {
            val (base, marker, number) = english.destructured
            words.add(base.trim())
            val season = number.toInt()
            if (season in 1..98) {
                val next = (season + 1).toString().padStart(number.length, '0')
                words.add("${base.trim()} $marker$next")
            }
        }
        val parts = title.split(separators).filter { it.length > 1 && it.any(Char::isLetter) }
        if (parts.size > 1) words.addAll(parts)
        return words.filter(String::isNotEmpty).take(LIMIT)
    }

    @JvmStatic
    fun merge(query: String?, remote: List<String>): List<String> =
        (local(query) + remote.map(String::trim)).filter(String::isNotEmpty).distinct().take(LIMIT)

    /** An invalid entry must not discard the remaining suggestions or the offline choices. */
    @JvmStatic
    fun parseResponse(json: String): List<String> = runCatching {
        val root = JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject
        val data = root?.get("data")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return@runCatching emptyList()
        data.mapNotNull { entry ->
            val item = entry.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            sequenceOf("title", "name").mapNotNull { key ->
                item.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                    ?.asString?.trim()?.takeIf(String::isNotEmpty)
            }.firstOrNull()
        }.distinct().take(LIMIT)
    }.getOrDefault(emptyList())

    private fun seasonNumber(value: String): Int? {
        value.toIntOrNull()?.let { return it }
        val normalized = value.replace('两', '二').replace('兩', '二').replace('〇', '零')
        if (normalized.length == 1 && normalized[0] in DIGITS) return DIGITS.indexOf(normalized[0])
        if (!Regex("[一二三四五六七八九]?十[一二三四五六七八九]?").matches(normalized)) return null
        val tens = normalized.substringBefore('十').singleOrNull()?.let(DIGITS::indexOf) ?: 1
        val ones = normalized.substringAfter('十').singleOrNull()?.let(DIGITS::indexOf) ?: 0
        return tens * 10 + ones
    }

    private fun chineseNumber(value: Int): String = when {
        value < 10 -> DIGITS[value].toString()
        else -> (if (value < 20) "" else DIGITS[value / 10].toString()) + "十" +
            (if (value % 10 == 0) "" else DIGITS[value % 10].toString())
    }
}
