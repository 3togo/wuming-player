package com.wuming.android.tv.bean;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.C;

import org.junit.Test;

public class HistoryTest {

    @Test
    public void savesPlaybackWithKnownDuration() {
        History history = history(1_000, 60_000);

        assertTrue(history.canSave());
    }

    @Test
    public void savesPlaybackWithUnknownDuration() {
        History history = history(1_000, C.TIME_UNSET);

        assertTrue(history.canSave());
    }

    @Test
    public void doesNotSaveBeforePlaybackAdvances() {
        assertFalse(history(C.TIME_UNSET, C.TIME_UNSET).canSave());
        assertFalse(history(0, 60_000).canSave());
    }

    @Test
    public void matchingEpisodePrefersCanonicalMediaUri() {
        History history = new History();
        history.setVodFlag("line-a");
        history.setVodRemarks("Episode 1");
        history.setEpisodeUrl("https://fallback.example/episode-1.mp4");
        history.setMediaUri("opendrive://local/asset/movie-1");

        assertEquals("opendrive://local/asset/movie-1",
                history.getPlaybackId("line-a",
                        Episode.create("Episode 1", "https://fresh.example/1.mp4")));
        assertEquals("https://fresh.example/2.mp4",
                history.getPlaybackId("line-a",
                        Episode.create("Episode 2", "https://fresh.example/2.mp4")));
        assertEquals("https://fresh.example/1.mp4",
                history.getPlaybackId("line-b",
                        Episode.create("Episode 1", "https://fresh.example/1.mp4")));
        history.setMediaUri(null);
        assertEquals("https://fallback.example/episode-1.mp4",
                history.getPlaybackId("line-a", Episode.create("Episode 1", "")));
    }

    @Test
    public void currentProviderRouteBeatsStaleCanonicalHistory() {
        History history = new History();
        history.setVodFlag("Baidu");
        history.setVodRemarks("Episode 1");
        history.setMediaUri("opendrive://local/asset/01234567-89ab-cdef-0123-456789abcdef");
        String current = "opendrive-deferred:baidu:01234567-89ab-cdef-0123-456789abcdef";

        assertEquals(current,
                history.getPlaybackId("Baidu", Episode.create("Episode 1", current)));
    }

    @Test
    public void invalidCanonicalMediaUriFallsBackWithoutReplacingEpisodeUrl() {
        History history = new History();
        history.setVodFlag("line-a");
        history.setVodRemarks("Episode 1");
        history.setEpisodeUrl("https://fallback.example/episode-1.mp4");
        history.setMediaUri("opendrive://local/asset/id?token=secret");

        assertFalse(history.hasMediaUri());
        assertEquals("https://fresh.example/1.mp4",
                history.getPlaybackId("line-a",
                        Episode.create("Episode 1", "https://fresh.example/1.mp4")));
        assertEquals("https://fallback.example/episode-1.mp4", history.getEpisodeUrl());
    }

    @Test
    public void extractsVodIdFromCurrentAndLegacyHistoryKeys() {
        History current = new History();
        current.setCid(-93385703);
        current.setKey("push_agent@@@https://example.test/movie.mp4@@@-93385703");
        History legacy = new History();
        legacy.setCid(7);
        legacy.setKey("site@@@movie-1");

        assertEquals("https://example.test/movie.mp4", current.getVodId());
        assertEquals("movie-1", legacy.getVodId());
    }

    @Test
    public void preservesDelimiterInsideVodIdWhenSuffixMatchesCatalog() {
        History history = new History();
        history.setCid(7);
        history.setKey("site@@@folder@@@movie@@@7");

        assertEquals("folder@@@movie", history.getVodId());
    }

    private static History history(long position, long duration) {
        History history = new History();
        history.setPosition(position);
        history.setDuration(duration);
        return history;
    }
}
