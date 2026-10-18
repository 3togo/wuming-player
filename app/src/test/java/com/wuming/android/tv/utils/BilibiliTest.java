package com.wuming.android.tv.utils;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class BilibiliTest {

    @Test
    public void fillsMissingAnonymousPlaybackFields() {
        assertEquals("2046888448+1817903810+32+mpd",
                Bilibili.normalizeEpisodeUrl("2046888448+1817903810++", false));
    }

    @Test
    public void requestsHigherQualityForLoggedInPlayback() {
        assertEquals("2046888448+1817903810+80+mpd",
                Bilibili.normalizeEpisodeUrl("2046888448+1817903810++", true));
    }

    @Test
    public void leavesCompleteOrUnrelatedValuesUntouched() {
        assertEquals("1+2+64+mpd", Bilibili.normalizeEpisodeUrl("1+2+64+mpd", false));
        assertEquals("https://example.com/video", Bilibili.normalizeEpisodeUrl("https://example.com/video", true));
        assertEquals("-637532699+182849091++", Bilibili.normalizeEpisodeUrl("-637532699+182849091++", true));
    }
}
