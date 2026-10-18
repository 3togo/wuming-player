package com.wuming.tv.core.epg

import org.junit.Assert.assertEquals
import org.junit.Test

class ProgrammeGuidePolicyTest {

    @Test
    fun selectsNowAndNextFromUnorderedEntries() {
        val current = ProgrammeGuideEntry("news", "Now", 1000, 2000, "", "")
        val next = ProgrammeGuideEntry("news", "Next", 2000, 3000, "", "")

        val result = ProgrammeGuidePolicy.nowNext(listOf(next, current), 1500)

        assertEquals(current, result.now)
        assertEquals(next, result.next)
    }

    @Test
    fun rangeIncludesOverlappingProgrammes() {
        val overlap = ProgrammeGuideEntry("news", "Overlap", 500, 1500, "", "")
        val outside = ProgrammeGuideEntry("news", "Outside", 2000, 3000, "", "")

        assertEquals(listOf(overlap), ProgrammeGuidePolicy.within(listOf(outside, overlap), 1000, 2000))
    }
}
