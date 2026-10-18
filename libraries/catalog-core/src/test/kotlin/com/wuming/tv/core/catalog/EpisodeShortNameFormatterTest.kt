package com.wuming.tv.core.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

class EpisodeShortNameFormatterTest {

    @Test
    fun `indexOfIgnoringSpace matches ignoring whitespace`() {
        assertEquals(0, EpisodeShortNameFormatter.indexOfIgnoringSpace("The Matrix", "TheMatrix"))
        assertEquals(5, EpisodeShortNameFormatter.indexOfIgnoringSpace("The  Matrix 1999", "Matrix"))
        assertEquals(-1, EpisodeShortNameFormatter.indexOfIgnoringSpace("The Matrix", "Reloaded"))
    }

    @Test
    fun `replaceVodPart replaces matched vod portion`() {
        assertEquals("母荣子贵 14",
            EpisodeShortNameFormatter.replaceVodPart("母荣子贵 14", "母荣子贵"))
        assertEquals("庆余年 第14集",
            EpisodeShortNameFormatter.replaceVodPart("庆 余 年 第14集", "庆余年"))
        assertEquals("第14集",
            EpisodeShortNameFormatter.replaceVodPart("第14集", "庆余年"))
    }

    @Test
    fun `formatWithVod extracts episode marker after vod removal`() {
        assertEquals("第14集",
            EpisodeShortNameFormatter.formatWithVod("庆余年 第14集", "庆余年"))
    }

    @Test
    fun `formatWithVod falls back to episode display name when vod empty`() {
        assertEquals("Movie title",
            EpisodeShortNameFormatter.formatWithVod("Movie title.mp4", ""))
    }
}
