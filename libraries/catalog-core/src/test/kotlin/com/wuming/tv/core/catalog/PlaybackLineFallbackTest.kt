package com.wuming.tv.core.catalog

import org.junit.Assert.*
import org.junit.Test

class PlaybackLineFallbackTest {
    private fun line(flag: String, vararg names: String) = CatalogLine(flag, flag,
        names.mapIndexed { i, name -> CatalogEpisode(name, "$flag-$i") })

    @Test fun matchesReorderedEpisodesAndVisitsEachLineOnceThenStops() {
        val session = PlaybackLineFallback(listOf(
            line("FF", "第01集", "第02集"),
            line("missing", "Episode 1"),
            line("YZ", "Episode 2", "Episode 1"),
            line("RY", "第01集", "第02集"),
        ), 0, 1)
        assertEquals(PlaybackLineFallback.Selection(2, 0), session.next())
        assertEquals(PlaybackLineFallback.Selection(3, 1), session.next())
        repeat(3) { assertNull(session.next()) }
    }

    @Test fun wrapsToEarlierLinesWithoutReturningToTheInitialLine() {
        val session = PlaybackLineFallback(listOf(line("a", "1"), line("b", "1"), line("c", "1")), 1, 0)
        assertEquals(PlaybackLineFallback.Selection(2, 0), session.next())
        assertEquals(PlaybackLineFallback.Selection(0, 0), session.next())
        assertNull(session.next())
    }

    @Test fun ambiguousOrMissingEpisodeNeverFallsBackByArrayIndex() {
        val session = PlaybackLineFallback(listOf(line("a", "第1集", "第2集"),
            line("b", "Episode 2", "EP2"), line("c", "Episode 1", "Episode 3")), 0, 1)
        assertNull(session.next())
        assertNull(PlaybackLineFallback(emptyList(), 0, 0).next())
        assertNull(PlaybackLineFallback(listOf(line("a", "Episode 1")), 0, -1).next())
    }

    @Test fun aSingleNumberedEpisodeMustNotFallBackToADifferentEpisode() {
        assertNull(PlaybackLineFallback(listOf(line("a", "Episode 2"), line("b", "Episode 1")), 0, 0).next())
    }

    @Test fun singlePartFilmsCanUseDifferentQualityLabels() {
        val session = PlaybackLineFallback(listOf(line("a", "正片"), line("b", "HD")), 0, 0)
        assertEquals(PlaybackLineFallback.Selection(1, 0), session.next())
        assertNull(session.next())
    }
}
