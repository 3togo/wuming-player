package com.wuming.tv.core.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

class EpisodeDisplayNameTest {

    @Test
    fun cloudFilenameShowsTrailingEpisodeInsteadOfRepeatedTitle() {
        assertEquals("第14集", EpisodeDisplayName.format(
            "[4.76MB] 113.母荣子贵，被冷面赛车手宠上天（）未删减 14.mp4"))
    }

    @Test
    fun seasonEpisodeMarkerSurvivesFilenameCleanup() {
        assertEquals("S01E08", EpisodeDisplayName.format("A very long programme title.S01E08.mkv"))
    }

    @Test
    fun seasonEpisodeMarkerIsExtractedAnywhereWithAnyDigitCount() {
        assertEquals("S1E2", EpisodeDisplayName.format("S1E2 Episode title.mkv"))
        assertEquals("S01E08", EpisodeDisplayName.format("Show.s01e08.Episode title.1080p.mkv"))
        assertEquals("S123E12345", EpisodeDisplayName.format("Show-S123E12345-release.mkv"))
        assertEquals("S2026E2025", EpisodeDisplayName.format("Show.S2026E2025.mkv"))
        assertEquals("S01E02", EpisodeDisplayName.format("剧名S01E02国语版.mp4"))
    }

    @Test
    fun incompleteSeasonEpisodeMarkersRemainUnchanged() {
        assertEquals("SE", EpisodeDisplayName.format("SE.mkv"))
        assertEquals("S1E", EpisodeDisplayName.format("S1E.mkv"))
        assertEquals("SE2 bonus", EpisodeDisplayName.format("SE2 bonus.mkv"))
    }

    @Test
    fun ordinaryProviderLabelRemainsUnchanged() {
        assertEquals("第8集", EpisodeDisplayName.format("第8集"))
    }

    @Test
    fun videoFilenameWithoutEpisodeMarkerOnlyLosesExtension() {
        assertEquals("Movie title", EpisodeDisplayName.format("Movie title.mp4"))
    }

    @Test
    fun cloudDriveReleaseNameCollapsesToTitle() {
        assertEquals("The Last Blossom", EpisodeDisplayName.format(
            "[3.34GB] 2026《》 The.Last.Blossom.2026.1080p日语中字.mp4"))
        assertEquals("夜王 导演版 Night King Director's Cut", EpisodeDisplayName.format(
            "[2.65GB]夜王(导演版).Night.King.Director's.Cut.2026.HD1080P.粤语中字.mp4"))
        assertEquals("哈利波特与死亡圣器下 Harry Potter and the Deathly Hallows Part 2",
            EpisodeDisplayName.format(
                "[13.29GB] H哈利波特与死亡圣器下4K国英双语中英字幕2011 Harry.Potter.and.the.Deathly.Hallows.Part.2.2011.2160p.BluRay.x265.AAC.CHS.ENG-微博影音臻享.mp4"))
    }

    @Test
    fun allTechnicalNameFallsBackToPlaceholder() {
        assertEquals("正片", EpisodeDisplayName.format("[3.45GB]4K.mkv"))
    }

    @Test
    fun trailingNumberIsEpisodeOnlyForCjkTitles() {
        assertEquals("第14集", EpisodeDisplayName.format(
            "[4.76MB] 113.母荣子贵，被冷面赛车手宠上天（）未删减 14.mp4"))
        assertEquals("Blade Runner", EpisodeDisplayName.format(
            "[2.45GB]Blade.Runner.2049.2160p.BluRay.mkv"))
    }

    @Test
    fun copySuffixAndHashNoiseAreStripped() {
        assertEquals("爱", EpisodeDisplayName.format("爱-QK-5026-6ea07d.mp4"))
        assertEquals("O 德 赛 The Odyssey", EpisodeDisplayName.format(
            "[4.37GB] O.德.赛（2026） The.Odyssey.2026.1080p.AMZN.WEB-DL.H264.DDP.5.1.mkv (1)"))
    }
}
