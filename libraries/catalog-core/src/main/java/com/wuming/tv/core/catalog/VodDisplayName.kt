package com.wuming.tv.core.catalog

import com.wuming.tv.core.base.native.NativeLoader
import java.util.regex.Pattern

object VodDisplayName {

    private val CJK = NamePatterns.CJK

    private val nativeAvailable: Boolean get() = NativeLoader.available

    private val SHARED_TOKEN_BUCKETS = arrayOf(
        arrayOf("1080p", "2160p", "720p"),
        arrayOf("蓝光原盘", "蓝光1080P", "HD1080P", "hdr10\\+?"),
        arrayOf(
            "4K画质", "4K修复", "web-?dl", "web-?rip", "dvdrip?", "Blu-?Ray", "bd1080p?", "hd1080p?", "TrueHD",
            "dts-?hd(\\.ma)?", "全景声", "60fps", "60帧", "加长版资源", "完整版资源",
            "内封简繁字幕", "简繁字幕", "特效中英字幕", "中英字幕", "中英双字",
            "中文字幕", "粤语中字", "双语字幕", "无字幕", "内封字幕?",
            "日语中字", "法语中字", "韩语中字", "英语中字", "官方中字", "人工中字", "字幕组",
            "x26[45]", "h.?26[45]", "aac2?\\.?0?", "ddp\\d?(\\.5)?\\.?1?"
        ),
        arrayOf(
            "4K", "UHD", "HD", "BDRip", "BLURAY", "BD", "HDR", "SDR", "DV", "杜比视界", "WEB-DL", "WEBRip",
            "蓝光", "DVD", "iMAX", "DTS", "Atmos", "国配", "台配", "公映版", "高码率", "无水印",
            "10bit", "avc", "hevc", "ac3", "flac", "内封简繁", "无删减", "HDR10", "REMUX",
            "中字", "字幕", "简繁", "典藏版", "杜比",
            "bilibili", "bde4", "imax", "chs", "cht", "che", "eng", "hc", "hdrip", "hdtv"
        )
    )

    private val BRACKET_ONLY_TOKENS = arrayOf(
        "加长", "完整", "繁中", "中英", "双字", "导演版", "导演剪辑版"
    )

    private val SHARED_TOKENS_FLAT: Array<String> = flatten(SHARED_TOKEN_BUCKETS)
    private val BRACKET_TOKENS_FLAT: Array<String> = concat(SHARED_TOKENS_FLAT, BRACKET_ONLY_TOKENS)

    private val BRACKET_TECH: Pattern = compileBracketAlternation(BRACKET_TOKENS_FLAT)
    private val BRACKET_REGION: Pattern = Pattern.compile(
        "[\\[【（\\(]\\s*?(中国大陆|内地|港台|日本|韩国|欧美|美国|英国|法国|德国|土耳其|印度|泰国|西班牙|意大利|加拿大|俄罗斯|澳大利亚|日本动漫|韩剧|美剧|港剧|台剧|泰剧|日剧|国产)\\s*?[\\]】）\\)]"
    )
    private val BRACKET_GENRE: Pattern = Pattern.compile(
        "[\\[【（\\(]\\s*?(动作|喜剧|爱情|剧情|科幻|悬疑|犯罪|动画|奇幻|冒险|战争|恐怖|惊悚|纪录|家庭|音乐|歌舞|传记|历史|古装|武侠|灾难|西部|同性|运动|短片|真人秀|脱口秀|剧情片|动作片|喜剧片|科幻片|恐怖片|惊悚片|动画片|纪录片)\\s*?片?\\s*?[\\]】）\\)]"
    )
    private val TECH_BOUNDARY: Array<Pattern> = NamePatterns.compileBoundary(*SHARED_TOKENS_FLAT)
    private val YEAR: Pattern = Pattern.compile("(?<!\\d)(19|20)\\d{2}(?!\\d)")
    private val SEP: Pattern = Pattern.compile("[.·]+")
    private val BRACKETS: Pattern = Pattern.compile("[\\[\\]【】（）()]+")
    private val WS: Pattern = Pattern.compile("\\s+")
    private val LATIN_TAIL: Pattern =
        Pattern.compile("(?<=[\\u4e00-\\u9fff])\\s+[A-Za-z][A-Za-z0-9\\s]*$")

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
        var s = NamePatterns.SIZE_PREFIX.matcher(original).replaceFirst("")
        s = NamePatterns.EMPTY_BOOK.matcher(s).replaceAll("")
        if (NamePatterns.containsAny(s, '[', '【', '(', '（', ']', '】', ')', '）')) {
            s = BRACKET_TECH.matcher(s).replaceAll(" ")
            s = BRACKET_REGION.matcher(s).replaceAll(" ")
            s = BRACKET_GENRE.matcher(s).replaceAll(" ")
        }
        var changed = true
        var iterations = 0
        while (changed && iterations++ < 4) {
            changed = false
            for (p in TECH_BOUNDARY) {
                val r = p.matcher(s).replaceAll(" ")
                if (r != s) {
                    s = r
                    changed = true
                }
            }
        }
        s = YEAR.matcher(s).replaceAll(" ")
        s = NamePatterns.GROUP_TAIL.matcher(s).replaceFirst("")
        s = BRACKETS.matcher(s).replaceAll(" ")
        s = SEP.matcher(s).replaceAll(" ")
        s = LATIN_TAIL.matcher(s).replaceAll("")
        s = WS.matcher(s).replaceAll(" ").trim()
        return if (s.isEmpty()) original else s
    }

    @JvmStatic
    private external fun nativeFormat(value: String): String

    private fun flatten(buckets: Array<Array<String>>): Array<String> {
        var total = 0
        for (b in buckets) total += b.size
        val out = arrayOfNulls<String>(total)
        var i = 0
        for (b in buckets) {
            System.arraycopy(b, 0, out, i, b.size)
            i += b.size
        }
        @Suppress("UNCHECKED_CAST")
        return out as Array<String>
    }

    private fun concat(a: Array<String>, b: Array<String>): Array<String> {
        val out = arrayOfNulls<String>(a.size + b.size)
        System.arraycopy(a, 0, out, 0, a.size)
        System.arraycopy(b, 0, out, a.size, b.size)
        @Suppress("UNCHECKED_CAST")
        return out as Array<String>
    }

    private fun compileBracketAlternation(tokens: Array<String>): Pattern {
        val alt = StringBuilder(512)
        for (i in tokens.indices) {
            if (i > 0) alt.append('|')
            alt.append(tokens[i])
        }
        val re = "(?i)[\\[【（\\(]\\s*?(?:(?:" + alt + ")\\s*?){1,12}[\\]】）\\)]"
        return Pattern.compile(re)
    }
}
