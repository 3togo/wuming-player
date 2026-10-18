package com.wuming.tv.core.text

import java.util.Collections
import java.util.LinkedHashMap
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Regular expression utility class with pattern caching
 */
object RegexUtils {

    private const val MAX_CACHE_SIZE = 64

    // Site rules can name more patterns than a fixed-size guard could hold, which used to stop
    // caching altogether and recompile on every later call. Bounded LRU keeps the steady state
    // cached and drops the least recently used pattern instead.
    private val patternCache: MutableMap<String, Pattern> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Pattern>(MAX_CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pattern>): Boolean {
                return size > MAX_CACHE_SIZE
            }
        }
    )

    // Pre-compiled constant patterns
    private val PATTERN_EMAIL = Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
    private val PATTERN_URL = Pattern.compile("^(https?|ftp)://[^\\s/\$.?#].[^\\s]*$")
    private val PATTERN_IP = Pattern.compile("^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$")
    private val PATTERN_DOMAIN = Pattern.compile("^([a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,}$")
    private val PATTERN_NUMERIC = Pattern.compile("^[0-9]+$")
    private val PATTERN_ALPHANUMERIC = Pattern.compile("^[a-zA-Z0-9]+$")
    private val PATTERN_LETTERS = Pattern.compile("^[a-zA-Z]+$")
    private val PATTERN_HEX_COLOR = Pattern.compile("^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{3})$")
    private val PATTERN_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$")
    private val PATTERN_TIME = Pattern.compile("^\\d{2}:\\d{2}:\\d{2}$")
    private val PATTERN_DATETIME = Pattern.compile("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}$")
    private val PATTERN_EXTRACT_DOMAIN = Pattern.compile("^(?:https?://)?(?:www\\.)?([^:/\\s]+)")
    private val PATTERN_EXTRACT_PROTOCOL = Pattern.compile("^(https?)://")
    private val PATTERN_EXTRACT_PATH = Pattern.compile("^(?:https?://)?(?:[^/]+)(/.*)$")
    private val PATTERN_EXTRACT_QUERY = Pattern.compile("\\?(.*)$")
    private val PATTERN_EXTRACT_FILENAME = Pattern.compile("/([^/?]+)(?:\\?.*)?$")
    private val PATTERN_EXTRACT_EXT = Pattern.compile("\\.([a-zA-Z0-9]+)(?:[?#].*)?$")
    private val PATTERN_YOUTUBE = Pattern.compile("(?:youtube\\.com\\/(?:[^\\/]+\\/.+\\/|(?:v|e(?:mbed)?)\\/|.*[?&]v=)|youtu\\.be\\/)([^\"&?\\/\\s]{11})")
    private val PATTERN_VIMEO = Pattern.compile("vimeo\\.com\\/(?:channels\\/(?:\\w+\\/)?|groups\\/(?:[^\\/]*)\\/videos\\/|album\\/(?:\\d+)\\/video\\/|)(\\d+)(?:$|\\/|\\?)")
    private val PATTERN_M3U8 = Pattern.compile("(https?://[^\\s]+\\.m3u8[^\\s]*)")
    private val PATTERN_MP4 = Pattern.compile("(https?://[^\\s]+\\.mp4[^\\s]*)")
    private val PATTERN_ALL_URLS = Pattern.compile("(https?://[^\\s]+)")
    private val PATTERN_ALL_EMAILS = Pattern.compile("([A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,})")
    private val PATTERN_ALL_IPS = Pattern.compile("((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)")
    private val PATTERN_ESCAPE_REGEX = Pattern.compile("([\\\\.\\[\\]\\{\\}\\(\\)\\*\\+\\?\\^\\$\\|])")

    @JvmStatic
    fun getCachedPattern(regex: String): Pattern {
        val pattern = patternCache[regex]
        if (pattern != null) return pattern
        val compiled = Pattern.compile(regex)
        patternCache[regex] = compiled
        return compiled
    }

    /**
     * Check if string matches pattern
     */
    @JvmStatic
    fun matches(input: String?, regex: String?): Boolean {
        if (input == null || regex == null) return false
        return try {
            getCachedPattern(regex).matcher(input).matches()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Check if string contains pattern
     */
    @JvmStatic
    fun contains(input: String?, regex: String?): Boolean {
        if (input == null || regex == null) return false
        return try {
            getCachedPattern(regex).matcher(input).find()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Extract first match from string
     */
    @JvmStatic
    fun extract(input: String?, regex: String?): String {
        if (input == null || regex == null) return ""
        return try {
            val matcher = getCachedPattern(regex).matcher(input)
            if (matcher.find()) matcher.group(1) ?: "" else ""
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Extract all matches from string
     */
    @JvmStatic
    fun extractAll(input: String?, regex: String?): Array<String> {
        if (input == null || regex == null) return emptyArray()
        return try {
            collectGroups(getCachedPattern(regex).matcher(input), 1)
        } catch (e: Exception) {
            emptyArray()
        }
    }

    private fun collectGroups(matcher: Matcher, group: Int): Array<String> {
        val matches = mutableListOf<String>()
        while (matcher.find()) {
            matches.add(matcher.group(group))
        }
        return matches.toTypedArray()
    }

    /**
     * Replace all matches in string
     */
    @JvmStatic
    fun replaceAll(input: String?, regex: String?, replacement: String?): String {
        if (input == null || regex == null || replacement == null) return input ?: ""
        return try {
            getCachedPattern(regex).matcher(input).replaceAll(replacement)
        } catch (e: Exception) {
            input
        }
    }

    /**
     * Replace first match in string
     */
    @JvmStatic
    fun replaceFirst(input: String?, regex: String?, replacement: String?): String {
        if (input == null || regex == null || replacement == null) return input ?: ""
        return try {
            getCachedPattern(regex).matcher(input).replaceFirst(replacement)
        } catch (e: Exception) {
            input
        }
    }

    /**
     * Split string by regex
     */
    @JvmStatic
    fun split(input: String?, regex: String?): Array<String> {
        if (input == null || regex == null) return emptyArray()
        return try {
            getCachedPattern(regex).split(input)
        } catch (e: Exception) {
            emptyArray()
        }
    }

    /**
     * Count matches in string
     */
    @JvmStatic
    fun countMatches(input: String?, regex: String?): Int {
        if (input == null || regex == null) return 0
        return try {
            val matcher = getCachedPattern(regex).matcher(input)
            var count = 0
            while (matcher.find()) count++
            count
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Check if string is a valid email
     */
    @JvmStatic
    fun isEmail(input: String?): Boolean = input != null && PATTERN_EMAIL.matcher(input).matches()

    /**
     * Check if string is a valid URL
     */
    @JvmStatic
    fun isUrl(input: String?): Boolean = input != null && PATTERN_URL.matcher(input).matches()

    /**
     * Check if string is a valid IP address
     */
    @JvmStatic
    fun isIpAddress(input: String?): Boolean = input != null && PATTERN_IP.matcher(input).matches()

    /**
     * Check if string is a valid domain
     */
    @JvmStatic
    fun isDomain(input: String?): Boolean = input != null && PATTERN_DOMAIN.matcher(input).matches()

    /**
     * Check if string is numeric
     */
    @JvmStatic
    fun isNumeric(input: String?): Boolean = input != null && PATTERN_NUMERIC.matcher(input).matches()

    /**
     * Check if string is alphanumeric
     */
    @JvmStatic
    fun isAlphanumeric(input: String?): Boolean = input != null && PATTERN_ALPHANUMERIC.matcher(input).matches()

    /**
     * Check if string contains only letters
     */
    @JvmStatic
    fun isLetters(input: String?): Boolean = input != null && PATTERN_LETTERS.matcher(input).matches()

    /**
     * Check if string is a valid hex color
     */
    @JvmStatic
    fun isHexColor(input: String?): Boolean = input != null && PATTERN_HEX_COLOR.matcher(input).matches()

    /**
     * Check if string is a valid date (yyyy-MM-dd)
     */
    @JvmStatic
    fun isDate(input: String?): Boolean = input != null && PATTERN_DATE.matcher(input).matches()

    /**
     * Check if string is a valid time (HH:mm:ss)
     */
    @JvmStatic
    fun isTime(input: String?): Boolean = input != null && PATTERN_TIME.matcher(input).matches()

    /**
     * Check if string is a valid date time (yyyy-MM-dd HH:mm:ss)
     */
    @JvmStatic
    fun isDateTime(input: String?): Boolean = input != null && PATTERN_DATETIME.matcher(input).matches()

    /**
     * Extract domain from URL
     */
    @JvmStatic
    fun extractDomain(url: String?): String {
        if (url == null) return ""
        val m = PATTERN_EXTRACT_DOMAIN.matcher(url)
        return if (m.find()) m.group(1) ?: "" else ""
    }

    /**
     * Extract protocol from URL
     */
    @JvmStatic
    fun extractProtocol(url: String?): String {
        if (url == null) return ""
        val m = PATTERN_EXTRACT_PROTOCOL.matcher(url)
        return if (m.find()) m.group(1) ?: "" else ""
    }

    /**
     * Extract path from URL
     */
    @JvmStatic
    fun extractPath(url: String?): String {
        if (url == null) return ""
        val m = PATTERN_EXTRACT_PATH.matcher(url)
        return if (m.find()) m.group(1) ?: "" else ""
    }

    /**
     * Extract query parameters from URL
     */
    @JvmStatic
    fun extractQuery(url: String?): String {
        if (url == null) return ""
        val m = PATTERN_EXTRACT_QUERY.matcher(url)
        return if (m.find()) m.group(1) ?: "" else ""
    }

    /**
     * Extract filename from URL
     */
    @JvmStatic
    fun extractFilename(url: String?): String {
        if (url == null) return ""
        val m = PATTERN_EXTRACT_FILENAME.matcher(url)
        return if (m.find()) m.group(1) ?: "" else ""
    }

    /**
     * Extract file extension from filename or URL
     */
    @JvmStatic
    fun extractFileExtension(input: String?): String {
        if (input == null) return ""
        val m = PATTERN_EXTRACT_EXT.matcher(input)
        return if (m.find()) m.group(1) ?: "" else ""
    }

    /**
     * Extract video ID from YouTube URL
     */
    @JvmStatic
    fun extractYouTubeId(url: String?): String {
        if (url == null) return ""
        val m = PATTERN_YOUTUBE.matcher(url)
        return if (m.find()) m.group(1) ?: "" else ""
    }

    /**
     * Extract video ID from Vimeo URL
     */
    @JvmStatic
    fun extractVimeoId(url: String?): String {
        if (url == null) return ""
        val m = PATTERN_VIMEO.matcher(url)
        return if (m.find()) m.group(1) ?: "" else ""
    }

    /**
     * Extract M3U8 URL from text
     */
    @JvmStatic
    fun extractM3u8Url(text: String?): String {
        if (text == null) return ""
        val m = PATTERN_M3U8.matcher(text)
        return if (m.find()) m.group(1) ?: "" else ""
    }

    /**
     * Extract MP4 URL from text
     */
    @JvmStatic
    fun extractMp4Url(text: String?): String {
        if (text == null) return ""
        val m = PATTERN_MP4.matcher(text)
        return if (m.find()) m.group(1) ?: "" else ""
    }

    /**
     * Extract all URLs from text
     */
    @JvmStatic
    fun extractAllUrls(text: String?): Array<String> {
        if (text == null) return emptyArray()
        return collectGroups(PATTERN_ALL_URLS.matcher(text), 1)
    }

    /**
     * Extract all email addresses from text
     */
    @JvmStatic
    fun extractAllEmails(text: String?): Array<String> {
        if (text == null) return emptyArray()
        return collectGroups(PATTERN_ALL_EMAILS.matcher(text), 1)
    }

    /**
     * Extract all IP addresses from text
     */
    @JvmStatic
    fun extractAllIps(text: String?): Array<String> {
        if (text == null) return emptyArray()
        return collectGroups(PATTERN_ALL_IPS.matcher(text), 0)
    }

    /**
     * Escape regex special characters
     */
    @JvmStatic
    fun escapeRegex(input: String?): String {
        if (input == null) return ""
        return PATTERN_ESCAPE_REGEX.matcher(input).replaceAll("\\\\$1")
    }

    /**
     * Create regex pattern for wildcard matching
     */
    @JvmStatic
    fun wildcardToRegex(wildcard: String?): String {
        if (wildcard == null) return ""
        val escaped = escapeRegex(wildcard)
        return escaped.replace("\\*", ".*").replace("\\?", ".")
    }

    /**
     * Check if string matches wildcard pattern
     */
    @JvmStatic
    fun matchesWildcard(input: String?, wildcard: String?): Boolean {
        if (input == null || wildcard == null) return false
        val regex = wildcardToRegex(wildcard)
        return matches(input, regex)
    }

    /**
     * Compile pattern with flags
     */
    @JvmStatic
    fun compilePattern(regex: String?, flags: Int): Pattern? {
        return try {
            Pattern.compile(regex, flags)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Compile pattern with case-insensitive flag
     */
    @JvmStatic
    fun compilePatternCaseInsensitive(regex: String): Pattern? = compilePattern(regex, Pattern.CASE_INSENSITIVE)

    /**
     * Compile pattern with multiline flag
     */
    @JvmStatic
    fun compilePatternMultiline(regex: String): Pattern? = compilePattern(regex, Pattern.MULTILINE)

    /**
     * Compile pattern with dotall flag
     */
    @JvmStatic
    fun compilePatternDotall(regex: String): Pattern? = compilePattern(regex, Pattern.DOTALL)
}
