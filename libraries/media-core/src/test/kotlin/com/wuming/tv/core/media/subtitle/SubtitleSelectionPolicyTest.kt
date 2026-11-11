package com.wuming.tv.core.media.subtitle

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class SubtitleSelectionPolicyTest {
    @Test fun chineseScriptsAndRegions() {
        val traditional = Locale.forLanguageTag("zh-TW")
        assertEquals(listOf("zh-TW", "zh-Hant", "zh"), SubtitleSelectionPolicy.preferredLanguages(traditional))
        assertTrue(SubtitleSelectionPolicy.score(traditional, "zh_Hant") > SubtitleSelectionPolicy.score(traditional, "zh-Hans"))
        val explicit = Locale.forLanguageTag("zh-Hans-TW")
        assertTrue(SubtitleSelectionPolicy.score(explicit, "zh-CN") > SubtitleSelectionPolicy.score(explicit, "zh-Hant"))
        assertEquals(0, SubtitleSelectionPolicy.score(traditional, "und"))
        assertEquals(emptyList<String>(), SubtitleSelectionPolicy.preferredLanguages(Locale.ROOT))
    }
    @Test fun genericNonChineseLanguageOutranksAnUnrelatedRegion() {
        assertTrue(SubtitleSelectionPolicy.score(Locale.US, "en") > SubtitleSelectionPolicy.score(Locale.US, "en-GB"))
        assertEquals(listOf(4, 1), SubtitleSelectionPolicy.selectionFlags(Locale.US, listOf("en-GB", "en"), listOf(0, 0)))
    }

    @Test fun inferOnlyOneDefaultAndKeepExplicitFlags() {
        val locale = Locale.forLanguageTag("zh-Hant")
        assertEquals(listOf(4, 1, 4), SubtitleSelectionPolicy.selectionFlags(locale, listOf("en", "zh-TW", "zh-HK"), listOf(0,0,0)))
        assertEquals(listOf(4, 2, 4), SubtitleSelectionPolicy.selectionFlags(locale, listOf("en", "zh", "zh"), listOf(0,2,0)))
        assertEquals(listOf(1, 4), SubtitleSelectionPolicy.selectionFlags(Locale.US, listOf("und", "und"), listOf(0,0)))
        assertEquals(listOf(1), SubtitleSelectionPolicy.selectionFlags(Locale.US, listOf("und"), listOf(0)))
        assertEquals(listOf(3, 1), SubtitleSelectionPolicy.selectionFlags(Locale.US, listOf("en", "fr"), listOf(3,1)))
    }
}
