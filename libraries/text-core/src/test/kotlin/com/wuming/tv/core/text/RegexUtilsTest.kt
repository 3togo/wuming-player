package com.wuming.tv.core.text

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RegexUtilsTest {

    @Test
    fun cachedPatternsAreReused() {
        assertSame(RegexUtils.getCachedPattern("vod\\d+"), RegexUtils.getCachedPattern("vod\\d+"))
    }

    @Test
    fun patternsBeyondTheCacheCapacityKeepBeingReused() {
        for (index in 0 until 200) {
            RegexUtils.getCachedPattern("rule$index\\d+")
        }
        assertSame(
            RegexUtils.getCachedPattern("rule199\\d+"),
            RegexUtils.getCachedPattern("rule199\\d+")
        )
    }

    @Test
    fun invalidPatternsFailSafely() {
        assertFalse(RegexUtils.matches("value", "["))
        assertFalse(RegexUtils.contains("value", "["))
        assertEquals("", RegexUtils.extract("value", "["))
        assertNull(RegexUtils.compilePattern("[", 0))
    }

    @Test
    fun commonValidatorsAcceptAndRejectRepresentativeValues() {
        assertTrue(RegexUtils.isEmail("viewer@example.com"))
        assertFalse(RegexUtils.isEmail("viewer@example"))
        assertTrue(RegexUtils.isIpAddress("192.168.1.10"))
        assertFalse(RegexUtils.isIpAddress("300.168.1.10"))
        assertTrue(RegexUtils.isHexColor("#1a2B3c"))
        assertFalse(RegexUtils.isHexColor("1a2B3c"))
    }

    @Test
    fun urlPartsAreExtractedWithoutNetworkAccess() {
        val url = "https://www.example.com/video/movie.m3u8?token=abc"

        assertEquals("example.com", RegexUtils.extractDomain(url))
        assertEquals("https", RegexUtils.extractProtocol(url))
        assertEquals("/video/movie.m3u8?token=abc", RegexUtils.extractPath(url))
        assertEquals("token=abc", RegexUtils.extractQuery(url))
        assertEquals("movie.m3u8", RegexUtils.extractFilename(url))
        assertEquals("m3u8", RegexUtils.extractFileExtension(url))
    }

    @Test
    fun mediaLinksAndIdsAreExtracted() {
        assertEquals("dQw4w9WgXcQ", RegexUtils.extractYouTubeId("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("123456", RegexUtils.extractVimeoId("https://vimeo.com/123456"))
        assertEquals("https://cdn.example/live.m3u8", RegexUtils.extractM3u8Url("play https://cdn.example/live.m3u8 now"))
        assertEquals("https://cdn.example/movie.mp4", RegexUtils.extractMp4Url("play https://cdn.example/movie.mp4 now"))
    }

    @Test
    fun wildcardMatchingEscapesRegexCharacters() {
        assertTrue(RegexUtils.matchesWildcard("episode.12.m3u8", "episode.*.m3u8"))
        assertFalse(RegexUtils.matchesWildcard("episodeX12Xmp4", "episode.*.m3u8"))
    }

    @Test
    fun bulkExtractionPreservesEncounterOrder() {
        assertArrayEquals(arrayOf("one@example.com", "two@example.org"),
            RegexUtils.extractAllEmails("one@example.com then two@example.org"))
        assertArrayEquals(arrayOf("10.0.0.1", "192.168.1.2"),
            RegexUtils.extractAllIps("10.0.0.1 then 192.168.1.2"))
    }

    @Test
    fun genericExtractionReplacementSplittingAndCountingAreSafe() {
        assertArrayEquals(arrayOf("12", "34"), RegexUtils.extractAll("a12 b34", "(\\d+)"))
        assertEquals("a# b#", RegexUtils.replaceAll("a12 b34", "\\d+", "#"))
        assertEquals("a# b34", RegexUtils.replaceFirst("a12 b34", "\\d+", "#"))
        assertArrayEquals(arrayOf("a", "b", "c"), RegexUtils.split("a,b;c", "[,;]"))
        assertEquals(3, RegexUtils.countMatches("one two three", "\\w+"))
        assertEquals("a12 b34", RegexUtils.replaceAll("a12 b34", "[", "#"))
    }

    @Test
    fun completeValidatorAndBulkUrlMatrixCoversBoundaries() {
        assertTrue(RegexUtils.isUrl("https://example.com/video"))
        assertTrue(RegexUtils.isDomain("media.example.com"))
        assertTrue(RegexUtils.isNumeric("12345"))
        assertTrue(RegexUtils.isAlphanumeric("Abc123"))
        assertTrue(RegexUtils.isLetters("AbCd"))
        assertTrue(RegexUtils.isDate("2026-07-23"))
        assertTrue(RegexUtils.isTime("12:34:56"))
        assertTrue(RegexUtils.isDateTime("2026-07-23 12:34:56"))
        assertFalse(RegexUtils.isNumeric("12.3"))
        assertArrayEquals(arrayOf("https://one.test/a", "http://two.test/b"),
            RegexUtils.extractAllUrls("https://one.test/a http://two.test/b"))
    }

    @Test
    fun compiledPatternHelpersApplyRequestedFlags() {
        assertTrue(RegexUtils.compilePatternCaseInsensitive("vod")!!.matcher("VOD").matches())
        assertTrue(RegexUtils.compilePatternMultiline("^two$")!!.matcher("one\ntwo").find())
        assertTrue(RegexUtils.compilePatternDotall("one.*two")!!.matcher("one\ntwo").matches())
    }

    @Test
    fun splitUsesCachedPatternForRepeatedCalls() {
        val first = RegexUtils.split("a1b2c3", "\\d")
        val second = RegexUtils.split("x1y2z3", "\\d")
        assertArrayEquals(arrayOf("a", "b", "c"), first)
        assertArrayEquals(arrayOf("x", "y", "z"), second)
    }

    @Test
    fun splitHandlesNullAndInvalidInputSafely() {
        assertArrayEquals(emptyArray<String>(), RegexUtils.split(null, ","))
        assertArrayEquals(emptyArray<String>(), RegexUtils.split("a,b,c", null))
        assertArrayEquals(emptyArray<String>(), RegexUtils.split("a,b,c", "["))
    }

    @Test
    fun escapeRegexEscapesAllSpecialCharacters() {
        assertEquals("\\\\", RegexUtils.escapeRegex("\\"))
        assertEquals("\\.", RegexUtils.escapeRegex("."))
        assertEquals("\\[", RegexUtils.escapeRegex("["))
        assertEquals("\\]", RegexUtils.escapeRegex("]"))
        assertEquals("\\{", RegexUtils.escapeRegex("{"))
        assertEquals("\\}", RegexUtils.escapeRegex("}"))
        assertEquals("\\(", RegexUtils.escapeRegex("("))
        assertEquals("\\)", RegexUtils.escapeRegex(")"))
        assertEquals("\\*", RegexUtils.escapeRegex("*"))
        assertEquals("\\+", RegexUtils.escapeRegex("+"))
        assertEquals("\\?", RegexUtils.escapeRegex("?"))
        assertEquals("\\^", RegexUtils.escapeRegex("^"))
        assertEquals("\\$", RegexUtils.escapeRegex("$"))
        assertEquals("\\|", RegexUtils.escapeRegex("|"))
    }

    @Test
    fun escapeRegexDoesNotEscapeNormalCharacters() {
        assertEquals("abc123", RegexUtils.escapeRegex("abc123"))
        assertEquals("", RegexUtils.escapeRegex(null))
    }
}
