package com.wuming.android.tv.bean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

public class EpisodeLabelTest {

    @Test
    public void unplayedCloudEpisodesAreFormattedWithoutSelectionOrPersistentCache() {
        String[] names = {"01", "[172.03MB]2.mkv", "[179.11MB]3.mkv", "[269.26MB]4.mkv"};
        String[] labels = {"第01集", "第2集", "第3集", "第4集"};
        for (int i = 0; i < names.length; i++) {
            Episode episode = new Episode();
            episode.setName(names[i]);
            episode.setUrl("https://example.com/" + i);
            assertEquals(labels[i], episode.getDisplayName());
            episode.setActivated(true);
            assertEquals(labels[i], episode.getDisplayName());
            assertEquals(names[i], episode.getName());
            assertEquals("https://example.com/" + i, episode.getUrl());
            assertSame(episode.getDisplayName(), episode.getDisplayName());
        }
    }

    @Test
    public void renamedEpisodeInvalidatesItsDisplayLabel() {
        Episode episode = new Episode();
        episode.setName("[172.03MB]2.mkv");
        assertEquals("第2集", episode.getDisplayName());
        episode.setName("[179.11MB]3.mkv");
        assertEquals("第3集", episode.getDisplayName());
        episode.setName(null);
        assertEquals("", episode.getDisplayName());
    }
}
