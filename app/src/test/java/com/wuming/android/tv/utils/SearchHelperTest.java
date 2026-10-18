package com.wuming.android.tv.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class SearchHelperTest {

    @Test
    public void searchIsCaseInsensitiveAndSkipsNullItems() {
        List<String> result = SearchHelper.search(Arrays.asList("The Matrix", null, "Matrix Reloaded", "Arrival"), "matrix");

        assertEquals(Arrays.asList("The Matrix", "Matrix Reloaded"), result);
    }

    @Test
    public void fuzzyMatchRequiresCharactersInOrder() {
        assertTrue(SearchHelper.fuzzyMatch("the matrix", "tmx"));
        assertFalse(SearchHelper.fuzzyMatch("the matrix", "xmt"));
        assertFalse(SearchHelper.fuzzyMatch(null, "matrix"));
    }

    @Test
    public void levenshteinDistanceHandlesEditsAndNulls() {
        assertEquals(3, SearchHelper.levenshteinDistance("kitten", "sitting"));
        assertEquals(3, SearchHelper.levenshteinDistance(null, "vod"));
        assertEquals(0, SearchHelper.levenshteinDistance("same", "same"));
    }

    @Test
    public void similarityStaysWithinExpectedRange() {
        assertEquals(1.0, SearchHelper.similarity("same", "same"), 0.0);
        assertEquals(0.75, SearchHelper.similarity("test", "tent"), 0.0001);
        assertEquals(0.0, SearchHelper.similarity(null, "test"), 0.0);
    }

    @Test
    public void rankedSearchPlacesExactMatchFirst() {
        List<SearchHelper.SearchResult> result = SearchHelper.searchRanked(
                Arrays.asList("Matrix Reloaded", "Matrix", "Matri"), "Matrix");

        assertEquals("Matrix", result.get(0).text);
        assertEquals(1.0, result.get(0).score, 0.0);
    }

    @Test
    public void compoundSearchSupportsAndOrAndExclude() {
        List<String> items = Arrays.asList("Star Trek Discovery", "Star Wars", "Discovery Channel");

        assertEquals(List.of("Star Trek Discovery"), SearchHelper.searchAnd(items, List.of("star", "discovery")));
        assertEquals(items, SearchHelper.searchOr(items, List.of("star", "discovery")));
        assertEquals(List.of("Star Wars"), SearchHelper.searchExclude(items, "discovery"));
    }

    @Test
    public void invalidRegexReturnsAnEmptyResult() {
        assertTrue(SearchHelper.searchRegex(List.of("one", "two"), "[").isEmpty());
    }

    @Test
    public void highlightingMarksEveryCaseInsensitiveOccurrence() {
        assertEquals("<b>Vod</b> and <b>VOD</b>", SearchHelper.highlightHtml("Vod and VOD", "vod"));
        assertEquals("unchanged", SearchHelper.highlightHtml("unchanged", "vod"));
    }

    @Test
    public void wordMatchingHandlesOrderAndPartialOverlap() {
        assertTrue(SearchHelper.containsAllWords("The Wild Robot Movie", "robot wild"));
        assertFalse(SearchHelper.containsAllWords("The Wild Robot", "robot sequel"));
        assertEquals(1.0 / 3.0, SearchHelper.wordSimilarity("wild robot", "robot movie"), 0.0001);
    }
}
