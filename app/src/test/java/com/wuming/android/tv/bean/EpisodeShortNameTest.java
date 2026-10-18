package com.wuming.android.tv.bean;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Covers the VOD-superset rule in {@link EpisodeShortName}. The runner itself needs
 * Android preferences, so only the pure helpers are exercised here.
 */
public class EpisodeShortNameTest {

    @Test
    public void matchesEvenWhenSpacingDiffersFromShortenedVodTitle() {
        assertEquals(0, EpisodeShortName.indexOfIgnoringSpace("The Matrix", "TheMatrix"));
        assertEquals(5, EpisodeShortName.indexOfIgnoringSpace("The  Matrix 1999", "Matrix"));
        assertEquals(-1, EpisodeShortName.indexOfIgnoringSpace("The Matrix", "Reloaded"));
    }

    @Test
    public void replaceVodPartRewritesOnlyTheMatchedPortion() {
        assertEquals("母荣子贵 14",
                EpisodeShortName.replaceVodPart("母荣子贵 14", "母荣子贵"));
        assertEquals("庆余年 第14集",
                EpisodeShortName.replaceVodPart("庆 余 年 第14集", "庆余年"));
        assertEquals("第14集",
                EpisodeShortName.replaceVodPart("第14集", "庆余年"));
    }

    @Test
    public void supersetEpisodeNameDropsTheRepeatedVodTitle() {
        assertEquals("第14集", EpisodeShortName.formatWithVod(
                "母荣子贵，被冷面赛车手宠上天 14.mp4", "母荣子贵，被冷面赛车手宠上天"));
    }

    @Test
    public void unrelatedEpisodeNameKeepsItsOwnTitle() {
        assertEquals("Movie title", EpisodeShortName.formatWithVod(
                "Movie title.mp4", "Something else"));
    }

    @Test
    public void missingVodContextFallsBackToEpisodeShortening() {
        assertEquals("Movie title", EpisodeShortName.formatWithVod("Movie title.mp4", ""));
    }
}
