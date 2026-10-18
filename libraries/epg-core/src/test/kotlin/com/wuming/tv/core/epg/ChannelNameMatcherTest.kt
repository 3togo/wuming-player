package com.wuming.tv.core.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelNameMatcherTest {

    @Test
    fun removesPresentationNoiseButPreservesChannelFamily() {
        assertEquals("湖南", ChannelNameMatcher.normalize("湖南卫视 HD"))
        assertEquals("cctv 5", ChannelNameMatcher.normalize("CCTV-5 高清"))
    }

    @Test
    fun findsExactNormalizedAlias() {
        val match = ChannelNameMatcher.findBest(
            listOf("湖南卫视 HD"), listOf("CCTV-5", "湖南高清"), 0.86)

        assertNotNull(match)
        assertEquals("湖南高清", match!!.candidate)
        assertEquals(ChannelNameMatch.Kind.EXACT, match.kind)
    }

    @Test
    fun conflictingChannelNumbersAreNeverFuzzyMatched() {
        assertEquals(0.0, ChannelNameMatcher.match("CCTV-5", "CCTV-6 HD").score, 0.0)
        assertNull(ChannelNameMatcher.findBest(listOf("CCTV-5"), listOf("CCTV-6"), 0.1))
    }

    @Test
    fun normalizeHandlesNfkcCompatibilityCharacters() {
        val normalized = ChannelNameMatcher.normalize("ＣＣＴＶ１")
        assertEquals("cctv1", normalized.replace(" ", ""))
    }

    @Test
    fun normalizeStripsQualityTokensInChinese() {
        assertEquals("湖南", ChannelNameMatcher.normalize("湖南超清"))
        assertEquals("北京", ChannelNameMatcher.normalize("北京标清"))
        assertEquals("东方", ChannelNameMatcher.normalize("东方直播"))
    }

    @Test
    fun normalizeStripsTrailingTypeSuffixes() {
        assertEquals("湖南", ChannelNameMatcher.normalize("湖南卫视"))
        assertEquals("北京", ChannelNameMatcher.normalize("北京电视台"))
        assertEquals("广东", ChannelNameMatcher.normalize("广东频道"))
    }

    @Test
    fun normalizeCollapsesMultipleSpaces() {
        assertEquals("cctv 1", ChannelNameMatcher.normalize("CCTV   1"))
        assertEquals("cctv 1", ChannelNameMatcher.normalize("CCTV-1 HD"))
    }

    @Test
    fun normalizeHandlesEmptyAndWhitespaceInput() {
        assertEquals("", ChannelNameMatcher.normalize(""))
        assertEquals("", ChannelNameMatcher.normalize("   "))
    }

    @Test
    fun matchReturnsContainsForSubstringRelationship() {
        val match = ChannelNameMatcher.match("cctv 1", "cctv")
        assertEquals(ChannelNameMatch.Kind.CONTAINS, match.kind)
        assertEquals(0.90, match.score, 0.001)
    }

    @Test
    fun matchReturnsFuzzyForSimilarButNotContainedNames() {
        val match = ChannelNameMatcher.match("hunan", "hunantv")
        assertEquals(ChannelNameMatch.Kind.CONTAINS, match.kind)
    }

    @Test
    fun matchReturnsZeroScoreForEmptyNormalizedInput() {
        val match = ChannelNameMatcher.match("", "cctv")
        assertEquals(0.0, match.score, 0.0)
    }

    @Test
    fun findBestReturnsNullWhenNoCandidateMeetsMinimumScore() {
        assertNull(ChannelNameMatcher.findBest(listOf("abc"), listOf("xyz"), 0.9))
    }

    @Test
    fun findBestRejectsBlankNames() {
        assertNull(ChannelNameMatcher.findBest(listOf("  "), listOf("cctv"), 0.5))
        assertNull(ChannelNameMatcher.findBest(listOf("cctv"), listOf("  "), 0.5))
    }
}
