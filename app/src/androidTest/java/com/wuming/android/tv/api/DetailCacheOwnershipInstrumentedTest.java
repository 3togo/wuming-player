package com.wuming.android.tv.api;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.wuming.android.tv.bean.Episode;
import com.wuming.android.tv.bean.Flag;
import com.wuming.android.tv.bean.Result;
import com.wuming.android.tv.bean.Vod;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class DetailCacheOwnershipInstrumentedTest {
    @Test public void reopeningDetailDoesNotInheritPreviousScreensSelectionOrListEdits() {
        Vod vod = new Vod();
        vod.setName("fixture");
        vod.setId("fixture-id");
        vod.setPlayFrom("direct");
        vod.setPlayUrl("episode one$http://localhost/one.mp4#episode two$http://localhost/two.mp4");
        Result original = Result.vod(vod.setFlags());
        SiteApi.DetailCacheEntry cache = new SiteApi.DetailCacheEntry(original);
        for (int i = 0; i < 10; i++) {
            Result screen = i == 0 ? original : cache.getResult();
            Flag flag = screen.getVod().getFlags().get(0);
            assertEquals(2, flag.getEpisodes().size());
            assertFalse(flag.isActivated());
            Episode episode = flag.getEpisodes().get(0);
            assertFalse(episode.isActivated());
            flag.setActivated(flag);
            flag.toggle(true, episode);
            flag.getEpisodes().remove(1);
            screen.getVod().setName("changed by screen");
            Result next = cache.getResult();
            assertNotSame(screen, next);
            assertNotSame(flag, next.getVod().getFlags().get(0));
            assertEquals("fixture", next.getVod().getName());
            assertEquals(2, next.getVod().getFlags().get(0).getEpisodes().size());
            assertFalse(next.getVod().getFlags().get(0).isActivated());
            assertFalse(next.getVod().getFlags().get(0).getEpisodes().get(0).isActivated());
            assertEquals("http://localhost/one.mp4", next.getVod().getFlags().get(0).getEpisodes().get(0).getUrl());
        }
    }
}
