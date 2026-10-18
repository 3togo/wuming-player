package com.wuming.android.tv.bean;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

public class EpisodeMatchTest {
    @Test
    public void strictLookupDoesNotResumeAtEpisodeTenWhenEpisodeOneIsMissing() {
        Flag flag = episodes("Episode 10", "Episode 11");
        assertNull(flag.find("Episode 1", true));
    }

    @Test
    public void strictLookupDoesNotUseEmptyOrSingleCharacterRemarks() {
        Flag flag = episodes("Movie", "Trailer");
        assertNull(flag.find("", true));
        assertNull(flag.find("M", true));
    }

    @Test
    public void lookupStillMatchesEpisodeNumbersAndUnnumberedPartialNames() {
        Flag numbered = episodes("第1集", "第10集");
        assertSame(numbered.getEpisodes().get(0), numbered.find("Episode 1", true));
        Flag named = episodes("Movie extended", "Trailer");
        assertSame(named.getEpisodes().get(0), named.find("Movie", true));
    }

    private static Flag episodes(String... names) {
        Flag flag = new Flag();
        for (String name : names) {
            flag.getEpisodes().add(Episode.create(name, "https://example.com/" + name));
        }
        return flag;
    }
}
