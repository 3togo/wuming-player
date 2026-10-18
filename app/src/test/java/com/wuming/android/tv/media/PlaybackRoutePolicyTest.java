package com.wuming.android.tv.media;

import static org.junit.Assert.assertEquals;

import com.wuming.android.tv.bean.Episode;
import com.wuming.android.tv.bean.History;
import com.wuming.android.tv.setting.AppSettings;

import org.junit.Test;

public class PlaybackRoutePolicyTest {

    @Test
    public void catVodUsesCurrentRawEpisodeInsteadOfOpenDriveHistory() {
        History history = historyWithCanonicalIdentity();
        Episode current = Episode.create("Episode 1", "https://catvod.example/fresh.mp4");

        assertEquals("https://catvod.example/fresh.mp4",
                PlaybackRoutePolicy.select(AppSettings.LINK_ROUTE_DIRECT, history,
                        "line-a", current));
    }

    @Test
    public void openDriveMayResumeCanonicalHistoryForAnUnroutedEpisode() {
        History history = historyWithCanonicalIdentity();
        Episode current = Episode.create("Episode 1", "https://provider.example/share");

        assertEquals("opendrive://local/asset/movie-1",
                PlaybackRoutePolicy.select(AppSettings.LINK_ROUTE_BUILTIN, history,
                        "line-a", current));
    }

    @Test
    public void currentOpenDriveProviderRouteBeatsCanonicalHistory() {
        History history = historyWithCanonicalIdentity();
        String routed = "opendrive-deferred:quark:01234567-89ab-cdef-0123-456789abcdef";

        assertEquals(routed,
                PlaybackRoutePolicy.select(AppSettings.LINK_ROUTE_BUILTIN, history,
                        "line-a", Episode.create("Episode 1", routed)));
    }

    private static History historyWithCanonicalIdentity() {
        History history = new History();
        history.setVodFlag("line-a");
        history.setVodRemarks("Episode 1");
        history.setEpisodeUrl("https://provider.example/old-share");
        history.setMediaUri("opendrive://local/asset/movie-1");
        return history;
    }
}
