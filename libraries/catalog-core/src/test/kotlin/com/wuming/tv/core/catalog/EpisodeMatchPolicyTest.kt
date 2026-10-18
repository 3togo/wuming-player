package com.wuming.tv.core.catalog

import org.junit.Assert.*
import org.junit.Test

class EpisodeMatchPolicyTest {
    @Test fun episodeNumbersIgnoreYearsBracketsAndEncodingQualityTokens() {
        assertEquals(12, EpisodeMatchPolicy.number("[Group] EP12 2026 1080p h264 mp4"))
        assertEquals(9, EpisodeMatchPolicy.number("第9集 (2026) 4k"))
        assertEquals(-1, EpisodeMatchPolicy.number("Movie 2026 1080p"))
        assertEquals(-1, EpisodeMatchPolicy.number("9999999999999999999999"))
    }
    @Test fun exactNamesPrecedeNumbersAndSubstringMatches() {
        assertEquals(100, EpisodeMatchPolicy.score("Episode 1", 1, "EPISODE 1", 9))
        assertEquals(80, EpisodeMatchPolicy.score("第1集", 1, "Episode one", 1))
        assertEquals(70, EpisodeMatchPolicy.score("Episode one extra", -1, "Episode one", -1))
        assertEquals(60, EpisodeMatchPolicy.score("Episode one", -1, "Episode one extra", -1))
    }

    @Test fun partialNamesDoNotMatchDifferentNumberedEpisodes() {
        assertEquals(0, EpisodeMatchPolicy.score("Episode 10", 10, "Episode 1", 1))
        assertEquals(0, EpisodeMatchPolicy.score("第10集", 10, "第1", 1))
        assertEquals(0, EpisodeMatchPolicy.score("Episode one extra", -1, "Episode one", 1))
    }

    @Test fun emptyAndSingleCharacterQueriesDoNotMatchBySubstring() {
        assertEquals(0, EpisodeMatchPolicy.score("Movie", -1, "", -1))
        assertEquals(0, EpisodeMatchPolicy.score("Movie", -1, "M", -1))
        assertEquals(100, EpisodeMatchPolicy.score("M", -1, "m", -1))
    }
}
