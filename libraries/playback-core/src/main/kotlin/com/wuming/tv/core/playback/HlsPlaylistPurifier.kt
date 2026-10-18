package com.wuming.tv.core.playback

import com.wuming.tv.core.base.native.NativeLoader
import java.math.BigDecimal
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * Produces compact labels for cloud-drive video filenames without changing provider identity.
 * Pure function: no side effects, no caching.
 */
object HlsPlaylistPurifier {

    private const val TAG_DISCONTINUITY = "#EXT-X-DISCONTINUITY"
    private const val TAG_MEDIA_DURATION = "#EXTINF"
    private const val TAG_ENDLIST = "#EXT-X-ENDLIST"
    private const val TAG_KEY = "#EXT-X-KEY"

    private val nativeAvailable: Boolean get() = NativeLoader.available

    private val REGEX_X_DISCONTINUITY = Pattern.compile("#EXT-X-DISCONTINUITY[\\s\\S]*?(?=#EXT-X-DISCONTINUITY|\$)")
    private val REGEX_MEDIA_DURATION = Pattern.compile("$TAG_MEDIA_DURATION:([\\d\\.]+)\\b")
    private val REGEX_URI = Pattern.compile("URI=\"(.+?)\"")
    private const val MAX_PATTERN_CACHE_SIZE = 64
    private val PATTERN_CACHE = ConcurrentHashMap<String, Pattern>()
    private const val TIMES_NO_AD = 15

    @JvmStatic
    fun isAd(regex: String): Boolean {
        return regex.contains(TAG_DISCONTINUITY) || regex.contains(TAG_MEDIA_DURATION) || regex.contains(TAG_ENDLIST) || regex.contains(TAG_KEY) || isDouble(regex)
    }

    @JvmStatic
    fun purify(tsUrlPre: String, m3u8Content: String?, adRules: List<String>?): String? {
        if (nativeAvailable && m3u8Content != null) {
            val rulesJson = if (adRules.isNullOrEmpty()) "" else com.google.gson.Gson().toJson(adRules)
            val result = nativePurify(tsUrlPre, m3u8Content, rulesJson)
            if (result != null) return result
        }
        return purify(tsUrlPre, m3u8Content, adRules) { base, reference -> URI.create(base).resolve(reference).toString() }
    }

    @JvmStatic
    fun purify(tsUrlPre: String, m3u8Content: String?, adRules: List<String>?,
               uriResolver: (String, String) -> String): String? {
        val removedSegments = intArrayOf(0)
        if (m3u8Content.isNullOrEmpty()) return null
        if (!m3u8Content.startsWith("#EXTM3U")) return null
        val result = removeMinorityUrl(tsUrlPre, m3u8Content, removedSegments)
        if (result != null && removedSegments[0] > 0) return result
        return applyRules(tsUrlPre, m3u8Content, adRules, uriResolver, removedSegments)
    }

    private fun maxPercent(preUrlMap: HashMap<String, Int>): Double {
        var maxTimes = 0
        var totalTimes = 0
        for ((_, value) in preUrlMap) {
            if (value > maxTimes) maxTimes = value
            totalTimes += value
        }
        return maxTimes * 1.0 / (totalTimes * 1.0)
    }

    private fun removeMinorityUrl(tsUrlPre: String, m3u8Content: String, removedSegments: IntArray): String? {
        val lineSplit = if (m3u8Content.contains("\r\n")) "\r\n" else "\n"
        val lines = m3u8Content.split(lineSplit).toMutableList()
        val preUrlMap = HashMap<String, Int>()
        for (line in lines) {
            if (line.isEmpty() || line[0] == '#') continue
            val ilast = line.lastIndexOf('.')
            if (ilast <= 4) continue
            val preUrl = line.substring(0, ilast - 4)
            preUrlMap.merge(preUrl, 1) { a, b -> a + b }
        }
        if (preUrlMap.size <= 1) return null
        var domainFiltering = false
        if (maxPercent(preUrlMap) < 0.8) {
            preUrlMap.clear()
            for (line in lines) {
                if (line.isEmpty() || line[0] == '#') continue
                if (!line.startsWith("http://") && !line.startsWith("https://")) return null
                val ifirst = line.indexOf('/', 9)
                if (ifirst <= 0) continue
                val preUrl = line.substring(0, ifirst)
                preUrlMap.merge(preUrl, 1) { a, b -> a + b }
            }
            if (preUrlMap.size <= 1) return null
            if (maxPercent(preUrlMap) < 0.8) return null
            var allDomainsExceedThreshold = true
            for (count in preUrlMap.values) {
                if (count <= TIMES_NO_AD) { allDomainsExceedThreshold = false; break }
            }
            if (allDomainsExceedThreshold) return null
            domainFiltering = true
        }
        var maxTimes = 0
        var maxTimesPreUrl = ""
        for ((key, value) in preUrlMap) {
            if (value > maxTimes) { maxTimesPreUrl = key; maxTimes = value }
        }
        if (maxTimes == 0) return null
        var dealedExtXKey = false
        for (i in lines.indices) {
            if (!dealedExtXKey && lines[i].startsWith("#EXT-X-KEY")) {
                val start = lines[i].indexOf("URI=\"")
                if (start != -1) {
                    val startIdx = start + "URI=\"".length
                    val end = lines[i].indexOf("\"", startIdx)
                    if (end != -1) {
                        val keyUrl = lines[i].substring(startIdx, end)
                        if (keyUrl.isNotEmpty() && !keyUrl.startsWith("http://") && !keyUrl.startsWith("https://")) {
                            val newKeyUrl = if (keyUrl[0] == '/') {
                                val ifirst = tsUrlPre.indexOf('/', 9)
                                if (ifirst > 0) tsUrlPre.substring(0, ifirst) + keyUrl else tsUrlPre + keyUrl
                            } else {
                                tsUrlPre + keyUrl
                            }
                            lines[i] = lines[i].replace("URI=\"$keyUrl\"", "URI=\"$newKeyUrl\"")
                        }
                    }
                    dealedExtXKey = true
                }
            }
            if (lines[i].isEmpty() || lines[i][0] == '#') continue
            if (!domainFiltering) {
                if (lines[i].startsWith(maxTimesPreUrl)) {
                    if (!lines[i].startsWith("http://") && !lines[i].startsWith("https://")) {
                        lines[i] = if (lines[i][0] == '/') {
                            val ifirst = tsUrlPre.indexOf('/', 9)
                            if (ifirst > 0) tsUrlPre.substring(0, ifirst) + lines[i] else tsUrlPre + lines[i]
                        } else {
                            tsUrlPre + lines[i]
                        }
                    }
                } else {
                    if (i > 0 && lines[i - 1].isNotEmpty() && lines[i - 1][0] == '#') lines[i - 1] = ""
                    lines[i] = ""
                    removedSegments[0]++
                }
            } else {
                var absoluteUrl = lines[i]
                if (!absoluteUrl.startsWith("http://") && !absoluteUrl.startsWith("https://")) {
                    absoluteUrl = if (absoluteUrl[0] == '/') {
                        val ifirst = tsUrlPre.indexOf('/', 9)
                        if (ifirst > 0) tsUrlPre.substring(0, ifirst) + absoluteUrl else tsUrlPre + absoluteUrl
                    } else {
                        tsUrlPre + absoluteUrl
                    }
                }
                val ifirst = absoluteUrl.indexOf('/', 9)
                val domain = if (ifirst > 0) absoluteUrl.substring(0, ifirst) else absoluteUrl
                val cnt = preUrlMap[domain]
                if (domain == maxTimesPreUrl || (cnt != null && cnt > TIMES_NO_AD)) {
                    lines[i] = absoluteUrl
                } else {
                    if (i > 0 && lines[i - 1].isNotEmpty() && lines[i - 1][0] == '#') lines[i - 1] = ""
                    lines[i] = ""
                    removedSegments[0]++
                }
            }
        }
        return lines.joinToString(lineSplit)
    }

    private fun applyRules(tsUrlPre: String, m3u8Content: String, adRules: List<String>?,
                           uriResolver: (String, String) -> String, removedSegments: IntArray): String? {
        val content = m3u8Content.replace("\r\n", "\n")
        val sb = StringBuilder()
        for (line in content.split("\n")) {
            sb.append(if (shouldResolve(line)) resolve(tsUrlPre, line, uriResolver) else line).append("\n")
        }
        if (adRules.isNullOrEmpty()) return null
        return clean(sb.toString(), adRules, removedSegments)
    }

    private fun clean(line: String, ads: List<String>, removedSegments: IntArray): String {
        var result = line
        var scan = false
        for (ad in ads) {
            if (ad.contains(TAG_DISCONTINUITY) || ad.contains(TAG_MEDIA_DURATION)) {
                result = scanAd(result, ad, removedSegments)
            } else if (isDouble(ad)) scan = true
        }
        return if (scan) scan(result, ads, removedSegments) else result
    }

    private fun scanAd(line: String, tagAd: String, removedSegments: IntArray): String {
        val m1 = pattern(tagAd).matcher(line)
        val needRemoveAd = mutableListOf<String>()
        while (m1.find()) {
            val group = m1.group()
            val groupCleaned = group.replace(TAG_ENDLIST, "")
            val m2 = REGEX_MEDIA_DURATION.matcher(group)
            var tCount = 0
            while (m2.find()) tCount++
            needRemoveAd.add(groupCleaned)
            removedSegments[0] += tCount
        }
        if (needRemoveAd.isEmpty()) return line
        needRemoveAd.sortByDescending { it.length }
        var result = line
        for (rem in needRemoveAd) result = result.replace(rem, "")
        return result
    }

    private fun scan(line: String, ads: List<String>, removedSegments: IntArray): String {
        val m1 = REGEX_X_DISCONTINUITY.matcher(line)
        val needRemoveAd = mutableListOf<String>()
        while (m1.find()) {
            val group = m1.group()
            val groupCleaned = group.replace(TAG_ENDLIST, "")
            val m2 = REGEX_MEDIA_DURATION.matcher(group)
            var ft = BigDecimal.ZERO
            var lt = BigDecimal.ZERO
            var t = BigDecimal.ZERO
            var tCount = 0
            while (m2.find()) {
                if (ft == BigDecimal.ZERO) ft = BigDecimal(m2.group(1))
                lt = BigDecimal(m2.group(1))
                t = t.add(lt)
                tCount++
            }
            val ftStr = ft.toString()
            val ltStr = lt.toString()
            val tStr = t.toString()
            for (ad in ads) {
                if (ad.startsWith("-")) {
                    val adClean = ad.substring(1)
                    if (ltStr.startsWith(adClean)) { needRemoveAd.add(groupCleaned); removedSegments[0] += tCount; break }
                } else {
                    if (ftStr.startsWith(ad) || tStr.startsWith(ad)) { needRemoveAd.add(groupCleaned); removedSegments[0] += tCount; break }
                }
            }
        }
        var result = line
        for (rem in needRemoveAd) result = result.replace(rem, "")
        return result
    }

    private fun isDouble(ad: String): Boolean {
        return try { ad.toDouble() != 0.0 } catch (_: Exception) { false }
    }

    private fun pattern(expression: String): Pattern {
        val cached = PATTERN_CACHE[expression]
        if (cached != null) return cached
        val compiled = Pattern.compile(expression)
        if (PATTERN_CACHE.size < MAX_PATTERN_CACHE_SIZE) {
            val previous = PATTERN_CACHE.putIfAbsent(expression, compiled)
            if (previous != null) return previous
        }
        return compiled
    }

    private fun shouldResolve(line: String): Boolean {
        return (!line.startsWith("#") && !line.startsWith("http")) || line.startsWith(TAG_KEY)
    }

    private fun resolve(base: String, line: String, uriResolver: (String, String) -> String): String {
        if (line.startsWith(TAG_KEY)) {
            val matcher = REGEX_URI.matcher(line)
            val value = if (matcher.find()) matcher.group(1) else null
            if (value == null) return line
            val resolved = uriResolver(base, value)
            return line.replace(value, resolved)
        } else {
            return uriResolver(base, line)
        }
    }

    @JvmStatic
    private external fun nativePurify(tsUrlPre: String, m3u8Content: String, adRulesJson: String): String?
}
