package com.wuming.android.tv.library;

import com.wuming.android.tv.bean.Episode;
import com.wuming.android.tv.bean.History;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlaylistEpisodeSelectionTest {

    @Test
    public void filtersOnlyChosenEpisodesOnChosenLine() {
        List<Episode> episodes = List.of(
                Episode.create("Episode 1", "one"),
                Episode.create("Episode 2", "two"),
                Episode.create("Episode 3", "three"));
        PlaylistEpisodeSelection selection = new PlaylistEpisodeSelection(
                "main", List.of("episode 1", "Episode 3"));

        assertTrue(selection.includes("main", episodes.get(0)));
        assertFalse(selection.includes("backup", episodes.get(0)));
        assertEquals(List.of(episodes.get(0), episodes.get(2)), selection.filter("main", episodes));
        assertTrue(selection.filter("backup", episodes).isEmpty());
    }

    @Test
    public void preparesPlaybackAndNeverSilentlyRestoresExcludedEpisodes() {
        History history = new History();
        PlaylistEpisodeSelection selection = new PlaylistEpisodeSelection(
                "preferred", List.of("Episode 2"));
        PlaylistPlaybackPolicy.prepareHistory(history, selection);

        assertEquals("preferred", history.getVodFlag());
        assertEquals("Episode 2", history.getVodRemarks());

        List<Episode> renamed = List.of(Episode.create("Part Two", "new-id"));
        assertTrue(PlaylistPlaybackPolicy.filter(selection, "preferred", renamed).isEmpty());
    }
}
