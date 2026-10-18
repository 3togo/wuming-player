package com.wuming.tv.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTextMatcherTest {

    @Test
    fun subsequenceMatchingRequiresCharactersInOrder() {
        assertTrue(SearchTextMatcher.fuzzyMatch("the matrix", "tmx"))
        assertFalse(SearchTextMatcher.fuzzyMatch("the matrix", "xmt"))
    }

    @Test
    fun editAndWordSimilarityAreDeterministic() {
        assertEquals(3, SearchTextMatcher.levenshteinDistance("kitten", "sitting"))
        assertEquals(0.75, SearchTextMatcher.similarity("test", "tent"), 0.0001)
        assertEquals(1.0 / 3.0,
            SearchTextMatcher.wordSimilarity("wild robot", "robot movie"), 0.0001)
    }

    @Test
    fun wordMatchingSupportsAllAndAnySemantics() {
        assertTrue(SearchTextMatcher.containsAllWords("The Wild Robot Movie", "robot wild"))
        assertFalse(SearchTextMatcher.containsAllWords("The Wild Robot", "robot sequel"))
        assertTrue(SearchTextMatcher.containsAnyWord("The Wild Robot", "sequel robot"))
    }

    @Test
    fun nullInputsReturnSafeDefaults() {
        assertFalse(SearchTextMatcher.fuzzyMatch(null, "query"))
        assertFalse(SearchTextMatcher.fuzzyMatch("text", null))
        assertEquals(3, SearchTextMatcher.levenshteinDistance(null, "abc"))
        assertEquals(3, SearchTextMatcher.levenshteinDistance("abc", null))
        assertEquals(0.0, SearchTextMatcher.similarity(null, "abc"), 0.0001)
        assertEquals(0.0, SearchTextMatcher.similarity("abc", null), 0.0001)
        assertFalse(SearchTextMatcher.containsAllWords(null, "query"))
        assertFalse(SearchTextMatcher.containsAllWords("text", null))
        assertFalse(SearchTextMatcher.containsAnyWord(null, "query"))
        assertEquals(0.0, SearchTextMatcher.wordSimilarity(null, "abc"), 0.0001)
    }

    @Test
    fun emptyAndIdenticalStringsAreHandledCorrectly() {
        assertEquals(0, SearchTextMatcher.levenshteinDistance("", ""))
        assertEquals(1.0, SearchTextMatcher.similarity("same", "same"), 0.0001)
        assertEquals(1.0, SearchTextMatcher.similarity("", ""), 0.0001)
        assertTrue(SearchTextMatcher.fuzzyMatch("anything", ""))
        assertEquals(3, SearchTextMatcher.levenshteinDistance("abc", "abcdef"))
    }

    @Test
    fun unicodeStringsAreHandledCorrectly() {
        assertEquals(0, SearchTextMatcher.levenshteinDistance("无间道", "无间道"))
        assertEquals(1.0, SearchTextMatcher.similarity("无间道", "无间道"), 0.0001)
        assertTrue(SearchTextMatcher.fuzzyMatch("无间道 Infernal Affairs", "无IA"))
        assertEquals(1, SearchTextMatcher.levenshteinDistance("无间道", "无间"))
    }
}
