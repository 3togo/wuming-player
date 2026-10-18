package com.wuming.tv.core.catalog

import com.wuming.tv.core.base.native.NativeLoader
import java.util.Locale
import java.util.regex.Pattern

/**
 * Produces compact labels for cloud-drive video filenames without changing provider identity.
 * Pure function: no side effects, no caching.
 */
object EpisodeDisplayName {

    private val CJK = NamePatterns.CJK
    @JvmField
    val FALLBACK = "正片"

    private val nativeAvailable: Boolean get() = NativeLoader.available

    private val VIDEO_EXTENSION = Pattern.compile(
        "(?i)\\.(?:mp4|m4v|mkv|webm|mov|avi|flv|ts|m2ts|wmv|iso|rmvb|mpg|mpeg)\\s*$")
    private val COPY_PAREN = Pattern.compile("\\s*[\\(（]\\d{1,3}[\\)）]\\s*$")
    private val COPY_UNDERSCORE = Pattern.compile("_\\d+\$")
    private val SPAM_PREFIX = Pattern.compile("^[A-Za-z](?=[" + CJK + "])")
    private val HASH_ID = Pattern.compile(
        "(?i)\\b[A-Za-z]{1,4}-\\d{2,6}-[a-f0-9]{4,}\\b")
    private val ID_FIELD = Pattern.compile("(?i)\\bID\\s*[:：]\\s*\\d+")
    private val YEAR_BRACKET = Pattern.compile("[\\(（【]\\s*(?:19|20)\\d{2}\\s*[\\)）】]")
    private val YEAR_PLAIN = Pattern.compile("(?<!\\d)(?:19|20)\\d{2}(?!\\d)")
    private val GENRE = Pattern.compile(
        "惊悚片|恐怖片|剧情片|科幻片|动作片|喜剧片|爱情片|战争片|纪录片|动画片|犯罪片|悬疑片|奇幻片|冒险片")
    private val REGION = Pattern.compile(
        "中国大陆|内地|美国|英国|法国|德国|日本|韩国|印度|泰国|澳大利亚|加拿大|土耳其|西班牙|意大利|俄罗斯")
    private val SEPARATORS = Pattern.compile("[.\\[\\]【】《》「」·（）()]+")
    private val WHITESPACE = Pattern.compile("\\s+")
    private val EDGE_JUNK = Pattern.compile("^[-_.,，、|\\s]+|[-_.,，、|\\s]+\$")

    /** Explicit season/episode identifiers can occur anywhere, before titles or release details. */
    private val SEASON_EPISODE_MARKER = Pattern.compile("S\\d+E\\d+", Pattern.CASE_INSENSITIVE)

    /** Trailing episode markers; a bare number must be space-separated and <=2 digits (Part 2 is not an episode). */
    private val EPISODE_MARKER = Pattern.compile(
        "(?i)(EP?\\s*\\d{1,4}|第\\s*\\d{1,4}\\s*[集话期]|(?<=^|\\s)\\d{1,2})\\s*\$")

    /** Resolution/source/codec/audio/subtitle/release tokens removed with latin boundary checks. */
    private val TECH = NamePatterns.compileBoundary(
        "2160p", "1080[pi]", "720p", "4k", "uhd",
        "blu-ray", "bluray", "bd1080p?", "hd1080p?", "web-dl", "web-?leaked",
        "dvdrip", "remux", "itunes", "viutv", "hkg", "hdtv", "bd",
        "x26[45]", "h.?26[45]", "avc", "hevc", "10bit", "dv",
        "aac2?\\.?0?", "ac3", "2ac3", "ddp\\d?(\\.5)?\\.?1?", "truehd\\d?(\\.\\d)?",
        "dts-?hd(\\.ma)?", "atmos", "flac",
        "chs", "cht", "che", "eng", "big5", "rus", "mandarinenglish", "mandarincantonese",
        "mandarin", "cantonese", "japanese", "english",
        "hc", "web", "bde4", "hkfact", "mp4ba", "movieshaw", "52zygx",
        "ffans", "bucrt", "dreamhd", "fgt", "rarbg",
        "amzn", "viu", "jkyy", "3audios", "[57]\\.1")

    /** Resolutions glued to words: NightKing1080P / 夜王4k. */
    private val GLUED_RESOLUTION = Pattern.compile(
        "(?i)(?<=[a-z0-9" + CJK + "])(?:1080|2160|720)p")
    private val GLUED_4K = Pattern.compile("(?i)(?<=[a-z0-9" + CJK + "])4k")

    /** Subtitle variants with typos/noise: 官方中字 / 官方这中字 / 人工中字. */
    private val CN_VARIANT = Pattern.compile("官方.{0,2}中字|人工.{0,2}中字")

    /** Longest-first ordering so "粤语中文字幕" wins over "字幕". */
    private val CN_TAGS = arrayOf(
        "微博影音臻享", "特效中英字幕", "粤语中文字幕", "内封简繁字幕", "国英双语", "国粤双语",
        "中日双语", "国英双字", "繁体中字", "粤语繁中", "中英字幕", "中英双字",
        "简繁字幕", "粤语中字", "日语中字", "法语中字", "韩语中字", "英语中字",
        "中文字幕", "双语字幕", "无字幕", "完整版资源", "加长版资源",
        "无水印", "高码率", "内封", "双语", "简繁", "字幕", "中字", "双字",
        "繁中", "中英", "微博", "臻享", "德影",
    )

    @JvmStatic
    fun format(value: String?): String {
        val original = value?.trim() ?: ""
        if (original.isEmpty()) return ""
        if (nativeAvailable) {
            try {
                val result = nativeFormat(original)
                if (result.isNotEmpty()) return result
            } catch (_: UnsatisfiedLinkError) {
            }
        }
        val seasonEpisode = SEASON_EPISODE_MARKER.matcher(original)
        if (seasonEpisode.find()) return seasonEpisode.group().uppercase(Locale.ROOT)
        val cleaned = clean(original)
        val marker = extractEpisodeMarker(cleaned)
        if (marker != null) return marker
        return if (cleaned.isEmpty()) FALLBACK else cleaned
    }

    private fun clean(original: String): String {
        var s = NamePatterns.SIZE_PREFIX.matcher(original).replaceFirst("")
        s = COPY_PAREN.matcher(s).replaceFirst("")
        s = COPY_UNDERSCORE.matcher(s).replaceFirst("")
        s = VIDEO_EXTENSION.matcher(s).replaceFirst("")
        s = SPAM_PREFIX.matcher(s).replaceFirst("")
        s = HASH_ID.matcher(s).replaceAll("")
        s = ID_FIELD.matcher(s).replaceAll("")
        if (s.indexOf('…') >= 0) s = s.replace('…', ' ')
        s = NamePatterns.GROUP_TAIL.matcher(s).replaceFirst("")
        s = NamePatterns.EMPTY_BOOK.matcher(s).replaceAll("")
        // TECH loop: cap iterations to guard against pathological inputs.
        var changed = true
        var iterations = 0
        while (changed && iterations++ < 4) {
            changed = false
            for (token in TECH) {
                val replaced = token.matcher(s).replaceAll(" ")
                if (replaced != s) {
                    s = replaced
                    changed = true
                }
            }
        }
        s = GLUED_RESOLUTION.matcher(s).replaceAll("")
        s = GLUED_4K.matcher(s).replaceAll("")
        s = YEAR_BRACKET.matcher(s).replaceAll(" ")
        s = GENRE.matcher(s).replaceAll(" ")
        s = REGION.matcher(s).replaceAll(" ")
        s = YEAR_PLAIN.matcher(s).replaceAll(" ")
        s = CN_VARIANT.matcher(s).replaceAll(" ")
        for (tag in CN_TAGS) {
            if (s.indexOf(tag[0]) < 0) continue // fast-skip: first char absent
            s = s.replace(tag, " ")
        }
        s = SEPARATORS.matcher(s).replaceAll(" ")
        s = WHITESPACE.matcher(s).replaceAll(" ")
        s = EDGE_JUNK.matcher(s).replaceAll("").trim()
        return s
    }

    private fun extractEpisodeMarker(cleaned: String): String? {
        if (cleaned.isEmpty()) return null
        val matcher = EPISODE_MARKER.matcher(cleaned)
        if (!matcher.find()) return null
        val marker = matcher.group(1).trim()
        val prefix = cleaned.substring(0, matcher.start()).trim()
        // For latin titles a trailing number is usually part of the title ("Part 2"), not an episode.
        if (marker.length <= 2 && marker.matches("\\d+".toRegex())) {
            if (prefix.isNotEmpty() && containsLatin(prefix)) return null
            return "第$marker" + "集"
        }
        return marker.replace("\\s+".toRegex(), "").uppercase(Locale.ROOT)
    }

    private fun containsLatin(s: CharSequence): Boolean {
        for (i in 0 until s.length) {
            val c = s[i]
            if ((c in 'a'..'z') || (c in 'A'..'Z')) return true
        }
        return false
    }

    @JvmStatic
    private external fun nativeFormat(value: String): String
}
