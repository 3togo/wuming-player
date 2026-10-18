package com.wuming.tv.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleNormalizerTest {

    @Test
    fun conversionIsInjectedRatherThanPlatformBound() {
        val normalizer = TitleNormalizer { value -> value.replace('無', '无').replace('間', '间') }

        assertEquals("无间道", normalizer.simplified("無間道"))
        assertTrue(normalizer.equivalent("無間道", "无间道"))
    }

    @Test
    fun normalizationIsLocaleIndependentAndSegmentsMixedScripts() {
        val normalizer = TitleNormalizer.identity()

        assertEquals("the matrix 1999", normalizer.clean("ＴＨＥ Matrix：1999"))
        assertEquals(listOf("無間道", "Infernal", "Affairs"),
            normalizer.scriptSegments("無間道 / Infernal Affairs"))
    }

    @Test
    fun cleanHandlesNullAndEmptyInput() {
        val normalizer = TitleNormalizer.identity()
        assertEquals("", normalizer.clean(null))
        assertEquals("", normalizer.clean(""))
        assertEquals("", normalizer.clean("   "))
    }

    @Test
    fun keyStripsSpacesFromComparisonText() {
        val normalizer = TitleNormalizer.identity()
        assertEquals("thematrix", normalizer.key("The Matrix"))
        assertEquals("thematrix1999", normalizer.key("The Matrix 1999"))
        assertEquals("无间道", normalizer.key("无间道"))
    }

    @Test
    fun comparisonTextWithIdentityConverterEqualsClean() {
        val normalizer = TitleNormalizer.identity()
        assertEquals(normalizer.clean("The Matrix"), normalizer.comparisonText("The Matrix"))
        assertEquals(normalizer.clean("无间道"), normalizer.comparisonText("无间道"))
    }

    @Test
    fun equivalentRejectsEmptyKeys() {
        val normalizer = TitleNormalizer.identity()
        assertFalse(normalizer.equivalent("", ""))
        assertFalse(normalizer.equivalent(null, null))
        assertTrue(normalizer.equivalent("The Matrix", "the matrix"))
    }

    @Test
    fun containsHanAndContainsLatinDetectScripts() {
        val normalizer = TitleNormalizer.identity()
        assertTrue(normalizer.containsHan("无间道"))
        assertFalse(normalizer.containsHan("Infernal Affairs"))
        assertTrue(normalizer.containsLatin("Infernal Affairs"))
        assertFalse(normalizer.containsLatin("无间道"))
        assertTrue(normalizer.containsHan("无间道 Infernal"))
        assertTrue(normalizer.containsLatin("无间道 Infernal"))
    }

    @Test
    fun scriptSegmentsHandleEmptyAndBlankInput() {
        val normalizer = TitleNormalizer.identity()
        assertEquals(emptyList<String>(), normalizer.scriptSegments(null))
        assertEquals(emptyList<String>(), normalizer.scriptSegments(""))
        assertEquals(emptyList<String>(), normalizer.scriptSegments("   "))
    }

    @Test
    fun nfkcNormalizationConvertsFullwidthCharacters() {
        val normalizer = TitleNormalizer.identity()
        assertEquals("abc123", normalizer.clean("ＡＢＣ１２３"))
        assertEquals("abc", normalizer.clean("ＡＢＣ"))
    }
}
