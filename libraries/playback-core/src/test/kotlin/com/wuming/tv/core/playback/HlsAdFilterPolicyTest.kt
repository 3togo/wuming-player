package com.wuming.tv.core.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class HlsAdFilterPolicyTest {
    @Test fun sharedPreparationPreservesFirstMatchingNonemptyGroupWithoutMergingOrTrimming() {
        val rules = listOf(HlsAdRule("empty", listOf("cdn.example")),
            HlsAdRule("first", listOf("cdn.example"), listOf(" 5.48", "5.48")),
            HlsAdRule("second", listOf("cdn.example"), listOf("9.0")))
        assertEquals(listOf(" 5.48", "5.48"), HlsAdFilterPolicy.firstMatchingPatterns("https://cdn.example/show/", rules))
        assertEquals(emptyList<String>(), HlsAdFilterPolicy.firstMatchingPatterns("https://other.example/show/", rules))
    }

    @Test
    fun `selects only rules matching the manifest host`() {
        val rules = listOf(
            HlsAdRule("matched", listOf("cdn.example"), listOf("5.48", "5.48")),
            HlsAdRule("other", listOf("other.example"), listOf("9.0")),
        )

        assertEquals(
            listOf("5.48"),
            HlsAdFilterPolicy.matchingPatterns("https://CDN.EXAMPLE/show/episode.m3u8", rules),
        )
    }
}
