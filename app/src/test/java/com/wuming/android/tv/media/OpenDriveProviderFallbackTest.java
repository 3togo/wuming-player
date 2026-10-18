package com.wuming.android.tv.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.wuming.android.tv.bean.Episode;
import com.wuming.android.tv.bean.Flag;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

public class OpenDriveProviderFallbackTest {

    @Test
    public void ucTaskFailureSelectsSameEpisodeBaiduRoute() {
        Flag uc = flag("UC", "S01E02", routed("uc", "uc-handle"));
        Flag baidu = flag("Any display label", "S01E02", routed("baidu", "baidu-handle"));

        OpenDriveProviderFallback.Candidate result = OpenDriveProviderFallback.findBaidu(
                List.of(uc, baidu), uc, uc.getEpisodes().get(0), "uc",
                "UC task request failed (HTTP 400, code 32011)");

        assertEquals(baidu, result.flag());
        assertEquals("S01E02", result.episode().getName());
    }

    @Test
    public void doesNotGuessBaiduFromDisplayLabel() {
        Flag uc = flag("UC", "S01E02", routed("uc", "uc-handle"));
        Flag fake = flag("百度", "S01E02", "https://example.com/video.mp4");

        assertNull(OpenDriveProviderFallback.findBaidu(List.of(uc, fake), uc,
                uc.getEpisodes().get(0), "uc", "UC task request failed (HTTP 400)"));
    }

    @Test
    public void unrelatedUcErrorsDoNotSwitchProvider() {
        Flag uc = flag("UC", "S01E02", routed("uc", "uc-handle"));
        Flag baidu = flag("Baidu", "S01E02", routed("baidu", "baidu-handle"));

        assertNull(OpenDriveProviderFallback.findBaidu(List.of(uc, baidu), uc,
                uc.getEpisodes().get(0), "uc", "UC session expired"));
    }

    private static Flag flag(String name, String episode, String url) {
        Flag flag = Flag.create(name);
        flag.getEpisodes().add(Episode.create(episode, url));
        return flag;
    }

    private static String routed(String provider, String handle) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(handle.getBytes(StandardCharsets.UTF_8));
        return "opendrive-deferred:" + provider + ":" + encoded;
    }
}
